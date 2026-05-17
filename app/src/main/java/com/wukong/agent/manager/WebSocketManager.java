package com.wukong.agent.manager;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.google.gson.Gson;
import com.wukong.agent.model.WebSocketMessage;
import com.wukong.agent.util.JsonUtils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class WebSocketManager {

    private static final String TAG = "WebSocketManager";
    private static final int RECONNECT_BASE_DELAY_MS = 1000;
    private static final int RECONNECT_MAX_DELAY_MS = 60000;

    public interface WebSocketEventListener {
        void onConnected();
        void onDisconnected(String reason);
        void onMessage(WebSocketMessage message);
        void onError(String errorMessage);
    }

    private OkHttpClient okHttpClient;
    private WebSocket webSocket;
    private WebSocketEventListener eventListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Gson gson = JsonUtils.getGson();

    private String serverUrl;
    private boolean isConnected = false;
    private boolean shouldReconnect = true;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    // Heartbeat
    private Runnable heartbeatRunnable;
    private long heartbeatIntervalMs = 30000;
    private long lastPongTime = 0;

    public WebSocketManager() {
        this.okHttpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build();
    }

    public void setEventListener(WebSocketEventListener listener) {
        this.eventListener = listener;
    }

    public void setServerUrl(String url) {
        this.serverUrl = url;
    }

    public void setHeartbeatIntervalMs(long ms) {
        this.heartbeatIntervalMs = ms;
    }

    public boolean isConnected() {
        return isConnected;
    }

    /**
     * Connect to WebSocket server.
     */
    public void connect() {
        if (isConnected) {
            Log.w(TAG, "Already connected");
            return;
        }

        if (serverUrl == null || serverUrl.isEmpty()) {
            Log.e(TAG, "Server URL not set");
            notifyError("Server URL not configured");
            return;
        }

        shouldReconnect = true;

        Request request = new Request.Builder()
                .url(serverUrl)
                .build();

        webSocket = okHttpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.i(TAG, "WebSocket connected to: " + serverUrl);
                isConnected = true;
                reconnectAttempts.set(0);
                startHeartbeat();
                notifyConnected();
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    WebSocketMessage msg = gson.fromJson(text, WebSocketMessage.class);
                    if (msg.isPong()) {
                        lastPongTime = System.currentTimeMillis();
                    } else {
                        notifyMessage(msg);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse message: " + text, e);
                }
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                Log.d(TAG, "Received binary message (unexpected)");
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                Log.i(TAG, "WebSocket closing: " + code + " " + reason);
                ws.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.i(TAG, "WebSocket closed: " + code + " " + reason);
                handleDisconnect("Closed: " + reason);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "WebSocket failure", t);
                handleDisconnect("Failure: " + t.getMessage());
            }
        });
    }

    /**
     * Disconnect from WebSocket server.
     */
    public void disconnect() {
        shouldReconnect = false;
        stopHeartbeat();

        if (webSocket != null) {
            webSocket.close(1000, "Client disconnect");
            webSocket = null;
        }
        isConnected = false;
    }

    /**
     * Send a chat message with audio data.
     */
    public boolean sendChatMessage(String sessionId, String audioBase64, boolean isFinal) {
        if (!isConnected || webSocket == null) {
            Log.w(TAG, "Cannot send: not connected");
            return false;
        }

        WebSocketMessage msg = WebSocketMessage.createChat(sessionId, audioBase64, isFinal);
        String json = gson.toJson(msg);
        return webSocket.send(json);
    }

    /**
     * Send raw JSON string.
     */
    public boolean sendRaw(String json) {
        if (!isConnected || webSocket == null) return false;
        return webSocket.send(json);
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatRunnable = () -> {
            if (isConnected && webSocket != null) {
                String ping = gson.toJson(WebSocketMessage.createPing());
                webSocket.send(ping);
                handler.postDelayed(heartbeatRunnable, heartbeatIntervalMs);
            }
        };
        handler.postDelayed(heartbeatRunnable, heartbeatIntervalMs);
    }

    private void stopHeartbeat() {
        if (heartbeatRunnable != null) {
            handler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
    }

    private void handleDisconnect(String reason) {
        boolean wasConnected = isConnected;
        isConnected = false;
        stopHeartbeat();
        notifyDisconnected(reason);

        if (shouldReconnect) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        int attempts = reconnectAttempts.getAndIncrement();
        long delay = Math.min(
            RECONNECT_BASE_DELAY_MS * (1L << Math.min(attempts, 6)),
            RECONNECT_MAX_DELAY_MS
        );

        Log.i(TAG, "Scheduling reconnect in " + delay + "ms (attempt " + (attempts + 1) + ")");

        handler.postDelayed(() -> {
            if (shouldReconnect && !isConnected) {
                Log.i(TAG, "Attempting to reconnect...");
                connect();
            }
        }, delay);
    }

    private void notifyConnected() {
        handler.post(() -> {
            if (eventListener != null) eventListener.onConnected();
        });
    }

    private void notifyDisconnected(String reason) {
        handler.post(() -> {
            if (eventListener != null) eventListener.onDisconnected(reason);
        });
    }

    private void notifyMessage(WebSocketMessage msg) {
        handler.post(() -> {
            if (eventListener != null) eventListener.onMessage(msg);
        });
    }

    private void notifyError(String error) {
        handler.post(() -> {
            if (eventListener != null) eventListener.onError(error);
        });
    }

    public void release() {
        disconnect();
        if (okHttpClient != null) {
            okHttpClient.dispatcher().executorService().shutdown();
            okHttpClient.connectionPool().evictAll();
        }
    }
}
