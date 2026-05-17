package com.wukong.agent.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.RemoteViews;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;

import com.ubtrobot.api.WkSdk;
import com.wukong.agent.R;
import com.wukong.agent.coordinator.RobotStateCoordinator;
import com.wukong.agent.data.db.WukongDatabase;
import com.wukong.agent.data.repository.ChatHistoryRepository;
import com.wukong.agent.data.repository.WakeUpLogRepository;
import com.wukong.agent.data.repository.ActionRepository;
import com.wukong.agent.manager.*;
import com.wukong.agent.model.RobotConfig;
import com.wukong.agent.model.WebSocketMessage;
import com.wukong.agent.settings.SettingsActivity;
import com.wukong.agent.statemachine.BusinessState;
import com.wukong.agent.statemachine.BusinessStateMachine;
import com.wukong.agent.statemachine.StateChangeListener;
import com.wukong.agent.util.AudioUtils;

public class WukongService extends Service implements
        StateChangeListener,
        WakeUpManager.WakeUpListener,
        AudioRecorderManager.AudioListener,
        WebSocketManager.WebSocketEventListener,
        TTSEngine.TTSListener {

    private static final String TAG = "WukongService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "wukong_service_channel";

    // Core components
    private BusinessStateMachine stateMachine;
    private RobotStateCoordinator coordinator;
    private ConfigManager configManager;
    private WakeUpManager wakeUpManager;
    private AudioRecorderManager audioRecorderManager;
    private WebSocketManager webSocketManager;
    private TTSEngine ttsEngine;
    private RobotActionManager robotActionManager;

    // Data
    private WukongDatabase database;
    private ChatHistoryRepository chatHistoryRepository;
    private WakeUpLogRepository wakeUpLogRepository;
    private ActionRepository actionRepository;

    // State
    private final Handler handler = new Handler(Looper.getMainLooper());
    private NotificationManager notificationManager;
    private RemoteViews notificationLayout;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "WukongService onCreate");

        initNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification(BusinessState.IDLE));

        initComponents();
        startStateMachine();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "WukongService onStartCommand");
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // No binding
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "WukongService onDestroy");
        cleanup();
        super.onDestroy();

        // Schedule restart
        sendBroadcast(new Intent("com.wukong.agent.RESTART_SERVICE"));
    }

    // ==================== Initialization ====================

    private void initComponents() {
        // Database
        database = WukongDatabase.getInstance(this);
        chatHistoryRepository = new ChatHistoryRepository(database.chatHistoryDao());
        wakeUpLogRepository = new WakeUpLogRepository(database.wakeUpLogDao());
        actionRepository = new ActionRepository(database.actionDao());

        // Config
        configManager = ConfigManager.getInstance(this);
        configManager.getConfigLiveData().observeForever(this::onConfigChanged);

        // State machine
        stateMachine = new BusinessStateMachine();
        stateMachine.addListener(this);
        applyConfigToStateMachine(configManager.getConfig());

        // Managers
        wakeUpManager = new WakeUpManager(this);
        wakeUpManager.setListener(this);

        audioRecorderManager = new AudioRecorderManager(this);
        audioRecorderManager.setListener(this);
        audioRecorderManager.setVadParameters(
            configManager.getVadEnergyThreshold(),
            configManager.getVadSilenceDurationMs());

        webSocketManager = new WebSocketManager();
        webSocketManager.setEventListener(this);
        webSocketManager.setServerUrl(configManager.getWsServerUrl());

        ttsEngine = new TTSEngine();
        ttsEngine.setListener(this);

        robotActionManager = new RobotActionManager(this);

        // Coordinator
        coordinator = new RobotStateCoordinator(this, stateMachine);
        coordinator.init();

        // Initialize robot SDK
         WkSdk.INSTANCE.init(this);

        // Initialize AIKit
        // wakeUpManager.init(appId, apiKey, apiSecret, workDir);

        // Initialize PreProcessedRecorder
        audioRecorderManager.init();

        // Connect WebSocket
        webSocketManager.connect();
    }

    private void startStateMachine() {
        stateMachine.transitionTo(BusinessState.IDLE);
        // IDLE state -> start wake word listening
        startWakeWordListening();
    }

    // ==================== State Change Handling ====================

    @Override
    public void onStateChanged(BusinessState oldState, BusinessState newState) {
        Log.i(TAG, "State: " + oldState + " -> " + newState);
        updateNotification(newState);

        switch (newState) {
            case IDLE:
                // Restart wake word listening
                startWakeWordListening();
                audioRecorderManager.stopRecording();
                break;

            case WAKEUP:
                // Wake word detected, prepare to record
                audioRecorderManager.startRecording();
                // Transition to RECORDING will happen after recorder is ready
                handler.postDelayed(() -> {
                    if (stateMachine.getCurrentState() == BusinessState.WAKEUP) {
                        stateMachine.transitionTo(BusinessState.RECORDING);
                    }
                }, 200); // Small delay for recorder to stabilize
                break;

            case RECORDING:
                // Already recording, WebSocket is receiving chunks
                // Stop wake word listening during recording
                wakeUpManager.stopListening();
                break;

            case PROCESSING:
                // Stop recording, send final marker
                audioRecorderManager.stopRecording();
                String sessionId = audioRecorderManager.getCurrentSessionId();
                if (sessionId != null) {
                    webSocketManager.sendChatMessage(sessionId, "", true);
                }
                break;

            case PLAYING:
                // TTS playback started by onMessage callback
                // Action started by onMessage callback
                break;
        }
    }

    @Override
    public void onStateError(BusinessState state, String errorMessage) {
        Log.e(TAG, "State error in " + state + ": " + errorMessage);
        // On error, return to IDLE
        if (stateMachine.getCurrentState() != BusinessState.IDLE) {
            stateMachine.forceTransitionTo(BusinessState.IDLE, "error: " + errorMessage);
        }
    }

    // ==================== WakeUp Callbacks ====================

    @Override
    public void onWakeUp(String keyword, int confidence) {
        Log.i(TAG, "Wake word detected: " + keyword + " confidence: " + confidence);

        BusinessState current = stateMachine.getCurrentState();

        if (current == BusinessState.IDLE) {
            // Normal wake up
            stateMachine.transitionTo(BusinessState.WAKEUP, "wake: " + keyword);
        } else if (current == BusinessState.PLAYING) {
            // Interrupt: stop TTS and start new recording
            ttsEngine.stopPlayback();
            robotActionManager.stopAction();
            stateMachine.forceTransitionTo(BusinessState.RECORDING, "interrupt: " + keyword);
        } else if (current == BusinessState.RECORDING) {
            // Already recording, ignore or restart
            Log.d(TAG, "Wake word during recording, ignoring");
        }
    }

    @Override
    public void onWakeUpError(String errorMessage) {
        Log.e(TAG, "Wake word error: " + errorMessage);
        stateMachine.notifyError(stateMachine.getCurrentState(), errorMessage);
    }

    // ==================== Audio Recorder Callbacks ====================

    @Override
    public void onAudioData(byte[] pcmData) {
        if (stateMachine.getCurrentState() != BusinessState.RECORDING) return;

        String base64 = AudioUtils.pcmToBase64(pcmData);
        String sessionId = audioRecorderManager.getCurrentSessionId();
        webSocketManager.sendChatMessage(sessionId, base64, false);
    }

    @Override
    public void onVadSpeechStart() {
        Log.d(TAG, "VAD: speech started");
    }

    @Override
    public void onVadSpeechEnd() {
        Log.d(TAG, "VAD: speech ended");
        if (stateMachine.getCurrentState() == BusinessState.RECORDING) {
            stateMachine.transitionTo(BusinessState.PROCESSING, "vad_end");
        }
    }

    @Override
    public void onRecordingError(String errorMessage) {
        Log.e(TAG, "Recording error: " + errorMessage);
        stateMachine.forceTransitionTo(BusinessState.IDLE, "recording_error");
    }

    // ==================== WebSocket Callbacks ====================

    @Override
    public void onConnected() {
        Log.i(TAG, "WebSocket connected");
    }

    @Override
    public void onDisconnected(String reason) {
        Log.w(TAG, "WebSocket disconnected: " + reason);
    }

    @Override
    public void onMessage(WebSocketMessage message) {
        if (message.isTts()) {
            handleTtsMessage(message);
        } else if (message.isError()) {
            Log.e(TAG, "Server error: " + message.getError());
            stateMachine.forceTransitionTo(BusinessState.IDLE, "server_error");
        }
    }

    @Override
    public void onError(String errorMessage) {
        Log.e(TAG, "WebSocket error: " + errorMessage);
    }

    private void handleTtsMessage(WebSocketMessage message) {
        if (stateMachine.getCurrentState() == BusinessState.PROCESSING) {
            stateMachine.transitionTo(BusinessState.PLAYING, "tts_start");
            ttsEngine.startPlayback();
        }

        if (message.getAudioBase64() != null && !message.getAudioBase64().isEmpty()) {
            ttsEngine.feedAudioData(message.getAudioBase64());
        }

        // Execute action if specified
        if (message.getAction() != null && !message.getAction().isEmpty()) {
            robotActionManager.playAction(message.getAction());
        }

        // Save text to chat history
        if (message.getText() != null && !message.getText().isEmpty()) {
            String sessionId = audioRecorderManager.getCurrentSessionId();
            chatHistoryRepository.insertAsync(
                new com.wukong.agent.data.entity.ChatHistoryEntity(
                    sessionId != null ? sessionId : "unknown",
                    "assistant",
                    message.getText(),
                    System.currentTimeMillis()),
                null);
        }

        if (message.isFinal()) {
            ttsEngine.finishFeed();
        }
    }

    // ==================== TTS Callbacks ====================

    @Override
    public void onPlaybackStart() {
        Log.i(TAG, "TTS playback started");
    }

    @Override
    public void onPlaybackComplete() {
        Log.i(TAG, "TTS playback completed");
        if (stateMachine.getCurrentState() == BusinessState.PLAYING) {
            stateMachine.transitionTo(BusinessState.IDLE, "tts_complete");
        }
    }

    @Override
    public void onPlaybackError(String errorMessage) {
        Log.e(TAG, "TTS playback error: " + errorMessage);
        stateMachine.forceTransitionTo(BusinessState.IDLE, "tts_error");
    }

    // ==================== Config Change ====================

    private void onConfigChanged(RobotConfig config) {
        if (config == null) return;

        // Apply timeout settings
        applyConfigToStateMachine(config);

        // Update VAD parameters
        audioRecorderManager.setVadParameters(
            config.getVadEnergyThreshold(),
            config.getVadSilenceDurationMs());

        // Update TTS volume
        ttsEngine.setVolume(config.getTtsVolume());

        // Update WebSocket URL if changed
        if (!config.getWsServerUrl().equals(webSocketManager.isConnected() ? "connected" : "")) {
            webSocketManager.setServerUrl(config.getWsServerUrl());
        }

        // Update wake word config
        wakeUpManager.updateWakeWordConfig(
            config.isWakeWukongEnabled(),
            config.isWakeNihaoEnabled(),
            config.getNcmWukong(),
            config.getNcmNihao());
    }

    private void applyConfigToStateMachine(RobotConfig config) {
        stateMachine.setRecordingTimeoutMs(config.getRecordingTimeoutMs());
        stateMachine.setProcessingTimeoutMs(config.getProcessingTimeoutMs());
        stateMachine.setPlayingTimeoutMs(config.getPlayingTimeoutMs());
    }

    // ==================== Wake Word Control ====================

    private void startWakeWordListening() {
        wakeUpManager.startListening();
        // Also start PreProcessedRecorder for wake word audio
        // PreProcessedRecorder.start() if not already started
        // PreProcessedRecorder.registerRecordListener(forWakeUp, AudioRecorder.Type.FOR_WAKEUP)
    }

    // ==================== Notification ====================

    private void initNotificationChannel() {
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notification_channel_desc));
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(BusinessState state) {
        Intent settingsIntent = new Intent(this, SettingsActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String contentText;
        switch (state) {
            case RECORDING:
                contentText = getString(R.string.notification_text_recording);
                break;
            case PLAYING:
                contentText = getString(R.string.notification_text_playing);
                break;
            default:
                contentText = getString(R.string.notification_text_idle);
                break;
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(contentText)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(BusinessState state) {
        // Android 13+ 没有 POST_NOTIFICATIONS 权限时，notificationManager.notify() 会被静默忽略
        // ForegroundService 的初始通知不受此限制，但后续更新需要权限
        if (!hasNotificationPermission()) {
            Log.d(TAG, "Skipping notification update: POST_NOTIFICATIONS not granted");
            return;
        }
        Notification notification = buildNotification(state);
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    /**
     * 检查是否有通知发布权限。
     * Android 13 (API 33) 之前不需要运行时权限，直接返回 true。
     */
    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    // ==================== Cleanup ====================

    private void cleanup() {
        if (coordinator != null) coordinator.release();
        if (wakeUpManager != null) wakeUpManager.release();
        if (audioRecorderManager != null) audioRecorderManager.release();
        if (webSocketManager != null) webSocketManager.release();
        if (ttsEngine != null) ttsEngine.release();
        if (robotActionManager != null) robotActionManager.release();
        if (stateMachine != null) stateMachine.cleanup();
        if (configManager != null) {
            configManager.getConfigLiveData().removeObserver(this::onConfigChanged);
            configManager.release();
        }
    }

    // ==================== Static Control ====================

    public static void start(Context context) {
        Intent intent = new Intent(context, WukongService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, WukongService.class);
        context.stopService(intent);
    }
}
