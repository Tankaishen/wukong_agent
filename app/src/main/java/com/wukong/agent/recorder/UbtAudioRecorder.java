package com.wukong.agent.recorder;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ubtrobot.api.PreProcessedRecorder;
import com.ubtrobot.recorder.AudioRecordListener;
import com.ubtrobot.recorder.AudioRecorder;
import com.wukong.agent.interfaces.IRecorder;
import com.wukong.agent.interfaces.IWakeUpEngine;
import com.wukong.agent.util.AudioUtils;
import com.wukong.agent.util.RetryUtils;
import com.wukong.agent.util.VADDetector;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IRecorder implementation using UBT PreProcessedRecorder SDK (6-mic array).
 *
 * Design:
 * - PreProcessedRecorder.start() is called once after successful init and stays running.
 *   Switching between WAKEUP and ASR is done by AtomicBoolean flags
 *   (listeners are registered once, data forwarding is gated by flags).
 * - Two AudioRecordListener instances: FOR_WAKEUP (mono 16kHz) and FOR_ASR (6-channel).
 * - FOR_WAKEUP audio is forwarded directly to IWakeUpEngine.feedAudioData().
 * - FOR_ASR audio has MIC1 extracted for ASR + VAD, then delivered to AudioListener.
 * - PreProcessedRecorder requires network connection before init.
 * - Hardware start may fail at boot (AudioFlinger not ready), so we retry with exponential backoff.
 */
public class UbtAudioRecorder implements IRecorder {

    private static final String TAG = "UbtAudioRecorder";
    private static final int SAMPLE_RATE = 16000;

    /** Max retries for starting recorder hardware (AudioFlinger may not be ready at boot) */
    private static final int MAX_START_RETRIES = 5;
    /** Base delay between retries (ms), doubles each retry: 1s, 2s, 4s, 8s, 16s */
    private static final long RETRY_BASE_DELAY_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // External dependencies
    private Context context;
    private IWakeUpEngine wakeUpEngine;
    private AudioListener asrListener;
    private VADDetector vadDetector;

    // State tracking
    private final AtomicBoolean recorderInitialized = new AtomicBoolean(false);
    private final AtomicBoolean recorderStarted = new AtomicBoolean(false);
    private final AtomicBoolean wakeupListening = new AtomicBoolean(false);
    private final AtomicBoolean asrRecording = new AtomicBoolean(false);
    private String currentSessionId;

    // PreProcessedRecorder listener instances (created once in constructor)
    private final AudioRecordListener wakeupRecordListener;
    private final AudioRecordListener asrRecordListener;

    public UbtAudioRecorder(Context context) {
        Log.i(TAG, "UbtAudioRecorder construct!");
        this.context = context.getApplicationContext();

        // FOR_WAKEUP listener: 16bit mono 16kHz PCM → forward to wake engine
        wakeupRecordListener = (data, length) -> {
            if (!wakeupListening.get()) return;
            if (wakeUpEngine != null && wakeUpEngine.isListening()) {
                if (length > 0) {
                    byte[] audioChunk = new byte[length];
                    System.arraycopy(data, 0, audioChunk, 0, length);
                    wakeUpEngine.feedAudioData(audioChunk);
                }
            }
        };

        // FOR_ASR listener: 6-channel PCM → extract MIC1 → VAD → deliver
        asrRecordListener = (data, length) -> {
            if (!asrRecording.get()) return;
            if (length <= 0) return;

            byte[] sixChannelData = new byte[length];
            System.arraycopy(data, 0, sixChannelData, 0, length);

            // Extract MIC1 (channel 0) from 6-channel data
            byte[] singleChannel = AudioUtils.extractChannel(sixChannelData, 0);

            // Run VAD on single-channel data
            if (vadDetector != null) {
                long chunkDurationMs = (singleChannel.length / 2) * 1000L / SAMPLE_RATE;
                vadDetector.processAudioChunk(singleChannel, chunkDurationMs);
            }

            // Deliver single-channel data to ASR listener
            if (asrListener != null) {
                asrListener.onAudioData(singleChannel);
            }
        };
    }

    // ==================== Configuration ====================

    @Override
    public void setWakeUpEngine(IWakeUpEngine engine) {
        this.wakeUpEngine = engine;
    }

    @Override
    public void setAsrListener(AudioListener listener) {
        this.asrListener = listener;
    }

    @Override
    public void setVadParameters(int energyThreshold, int silenceDurationMs) {
        this.vadDetector = new VADDetector(energyThreshold, silenceDurationMs);
        this.vadDetector.setListener(new VADDetector.VADListener() {
            @Override
            public void onSpeechStart() {
                handler.post(() -> {
                    if (asrListener != null) asrListener.onVadSpeechStart();
                });
            }

            @Override
            public void onSpeechEnd() {
                handler.post(() -> {
                    if (asrListener != null) asrListener.onVadSpeechEnd();
                });
            }
        });
    }

    // ==================== PreProcessedRecorder Lifecycle ====================

    @Override
    public void init(Context context, InitCallback callback) {
        // Use stored context if param is null (called from AudioRecorderManager which passes null)
        Context ctx = context != null ? context : this.context;
        if (ctx != null) {
            this.context = ctx.getApplicationContext();
        }

        int initStatus = PreProcessedRecorder.INSTANCE.getInitStatus();
        int status = PreProcessedRecorder.INSTANCE.getState();
        Log.d(TAG, "PreProcessedRecorder status:" + initStatus + " " + status);

        executor.execute(() -> {
            int retryCount = 0;
            while (!isNetworkAvailable()) {
                Log.w(TAG, "Network not ready, waiting for connection...retryCount:" + retryCount);
                RetryUtils.waitFor(retryCount, RETRY_BASE_DELAY_MS);
                ++retryCount;
            }
            try {
                PreProcessedRecorder.INSTANCE.init(this.context, result -> {
                    if (result == PreProcessedRecorder.INIT_STATE_SUCCESS) {
                        recorderInitialized.set(true);
                        Log.i(TAG, "PreProcessedRecorder initialized successfully");
                        // Register listeners ONCE after init
                        PreProcessedRecorder.INSTANCE.registerRecordListener(
                                wakeupRecordListener, AudioRecorder.Type.FOR_WAKEUP);
                        PreProcessedRecorder.INSTANCE.registerRecordListener(
                                asrRecordListener, AudioRecorder.Type.FOR_ASR);
                        // Auto-start the recorder hardware after successful init
                        startRecorderHardware(callback);
                    } else {
                        recorderInitialized.set(false);
                        Log.e(TAG, "PreProcessedRecorder init failed, state: " + result);
                        notifyError("PreProcessedRecorder init failed: " + result);
                    }
                    if (callback != null) callback.onInitComplete();
                    return kotlin.Unit.INSTANCE;
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to init PreProcessedRecorder", e);
                notifyError("Init failed: " + e.getMessage());
                if (callback != null) callback.onInitComplete();
            }
        });
    }

    private boolean isNetworkAvailable() {
        if (context == null) return false;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    /**
     * Start the PreProcessedRecorder hardware (mic array).
     * Retries with exponential backoff if start fails (AudioFlinger may not be
     * ready at boot time — error -1 from createRecord is common).
     */
    private void startRecorderHardware(InitCallback callback) {
        if (recorderStarted.get()) {
            Log.d(TAG, "Recorder hardware already started");
            return;
        }

        AtomicInteger retryCount = new AtomicInteger(0);

        while (retryCount.get() < MAX_START_RETRIES) {
            try {
                boolean success = PreProcessedRecorder.INSTANCE.start();
                if (success) {
                    recorderStarted.set(true);
                    Log.i(TAG, "PreProcessedRecorder hardware started" +
                          (retryCount.get() > 0 ? " (retry #" + retryCount.get() + ")" : ""));
                    handler.post(() -> {
                        if (callback != null) {
                            callback.onRecorderReady();
                        }
                    });
                    return;
                } else {
                    int attempt = retryCount.incrementAndGet();
                    if (attempt >= MAX_START_RETRIES) {
                        Log.e(TAG, "PreProcessedRecorder.start() failed after " + MAX_START_RETRIES + " retries");
                        notifyError("Failed to start recorder hardware after " + MAX_START_RETRIES + " retries");
                        return;
                    }
                    long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                    Log.w(TAG, "PreProcessedRecorder.start() returned false, retry #" + attempt +
                          " in " + delay + "ms");
                    Thread.sleep(delay);
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Recorder start retry interrupted");
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                int attempt = retryCount.incrementAndGet();
                if (attempt >= MAX_START_RETRIES) {
                    Log.e(TAG, "Error starting recorder hardware after " + MAX_START_RETRIES + " retries", e);
                    notifyError("Start hardware failed: " + e.getMessage());
                    return;
                }
                long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                Log.w(TAG, "Exception starting hardware, retry #" + attempt + " in " + delay + "ms", e);
                try { Thread.sleep(delay); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Stop the PreProcessedRecorder hardware (mic array).
     * Only called during full shutdown (release).
     */
    private void stopRecorderHardware() {
        if (!recorderStarted.get()) return;
        try {
            PreProcessedRecorder.INSTANCE.unregisterRecordListener(
                    wakeupRecordListener, AudioRecorder.Type.FOR_WAKEUP);
            PreProcessedRecorder.INSTANCE.unregisterRecordListener(
                    asrRecordListener, AudioRecorder.Type.FOR_ASR);
            boolean success = PreProcessedRecorder.INSTANCE.stop();
            if (success) {
                recorderStarted.set(false);
                Log.i(TAG, "PreProcessedRecorder hardware stopped");
            } else {
                Log.w(TAG, "PreProcessedRecorder.stop() returned false");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recorder hardware", e);
        }
    }

    // ==================== Wakeup Audio Control ====================

    @Override
    public void startWakeupListening() {
        if (!recorderInitialized.get() || !recorderStarted.get()) {
            Log.w(TAG, "Cannot start wakeup listening: recorder not ready");
            return;
        }
        wakeupListening.set(true);
        Log.i(TAG, "Wakeup listening enabled (internal flag set)");
    }

    @Override
    public void stopWakeupListening() {
        wakeupListening.set(false);
        Log.i(TAG, "Wakeup listening disabled (internal flag cleared)");
    }

    // ==================== ASR Audio Control ====================

    @Override
    public String startAsrRecording() {
        currentSessionId = UUID.randomUUID().toString();
        if (vadDetector != null) {
            vadDetector.reset();
        }
        asrRecording.set(true);
        Log.i(TAG, "ASR recording enabled, session: " + currentSessionId);
        return currentSessionId;
    }

    @Override
    public void stopAsrRecording() {
        asrRecording.set(false);
        Log.i(TAG, "ASR recording disabled");
    }

    // ==================== State Queries ====================

    @Override
    public boolean isReady() {
        return recorderInitialized.get() && recorderStarted.get();
    }

    @Override
    public boolean isWakeupListening() {
        return wakeupListening.get();
    }

    @Override
    public boolean isAsrRecording() {
        return asrRecording.get();
    }

    @Override
    public String getCurrentSessionId() {
        return currentSessionId;
    }

    // ==================== Internal ====================

    private void notifyError(String message) {
        handler.post(() -> {
            if (asrListener != null) {
                asrListener.onRecordingError(message);
            }
        });
    }

    // ==================== Release ====================

    @Override
    public void release() {
        stopWakeupListening();
        stopAsrRecording();
        stopRecorderHardware();
        executor.shutdownNow();
        Log.i(TAG, "UbtAudioRecorder released");
    }
}
