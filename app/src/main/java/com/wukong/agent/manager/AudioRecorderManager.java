package com.wukong.agent.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ubtrobot.api.PreProcessedRecorder;
import com.ubtrobot.recorder.AudioRecordListener;
import com.ubtrobot.recorder.AudioRecorder;
import com.wukong.agent.interfaces.IWakeUpEngine;
import com.wukong.agent.util.AudioUtils;
import com.wukong.agent.util.VADDetector;

/**
 * AudioRecorderManager manages audio capture via the robot's PreProcessedRecorder.
 *
 * Design decisions:
 * - PreProcessedRecorder.start() is called once after successful init and stays running.
 *   Switching between WAKEUP and ASR is done by register/unregister listeners,
 *   NOT by stop/start — this avoids hardware re-initialization overhead and audio gaps.
 * - Two separate AudioRecordListener instances handle FOR_WAKEUP and FOR_ASR audio.
 * - FOR_WAKEUP audio (16bit mono 16kHz) is forwarded directly to IWakeUpEngine.feedAudioData().
 * - FOR_ASR audio (6-channel) has MIC1 extracted for ASR + VAD, then delivered to AudioListener.
 *
 * PreProcessedRecorder requires network connection before init.
 */
public class AudioRecorderManager {

    private static final String TAG = "AudioRecorderManager";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNELS = 1;
    private static final int BIT_DEPTH = 16;

    /**
     * Listener for ASR audio data (single-channel, post-VAD).
     * Wake word audio is delivered directly to IWakeUpEngine, not through this listener.
     */
    public interface AudioListener {
        void onAudioData(byte[] pcmData);       // 单声道 PCM 数据，可用于 ASR 发送
        void onVadSpeechStart();                // 检测到用户开始说话
        void onVadSpeechEnd();                  // 检测到用户停止说话
        void onRecordingError(String errorMessage); // 录音发生错误
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // External listeners
    private AudioListener asrListener;
    private IWakeUpEngine wakeUpEngine;
    private VADDetector vadDetector;

    // State tracking
    private final AtomicBoolean recorderInitialized = new AtomicBoolean(false);
    private final AtomicBoolean recorderStarted = new AtomicBoolean(false);
    private final AtomicBoolean wakeupListening = new AtomicBoolean(false);
    private final AtomicBoolean asrRecording = new AtomicBoolean(false);
    private String currentSessionId;

    // PreProcessedRecorder listener instances
    private final AudioRecordListener wakeupRecordListener;
    private final AudioRecordListener asrRecordListener;

    public AudioRecorderManager(Context context) {
        Log.i(TAG, "AudioRecorderManager construct!");
        this.context = context.getApplicationContext();

        // FOR_WAKEUP listener: 16bit mono PCM → forward to wake engine
        wakeupRecordListener = (data, length) -> {
            if (!wakeupListening.get()) return;
            if (wakeUpEngine != null && wakeUpEngine.isListening()) {
                // data[] contains mono 16kHz 16bit PCM for wake word detection
                // length is the number of valid bytes in data[]
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

    public void setAsrListener(AudioListener listener) {
        this.asrListener = listener;
    }

    /**
     * Set the wake engine to receive FOR_WAKEUP audio data.
     * Must be called before startWakeupListening().
     */
    public void setWakeUpEngine(IWakeUpEngine engine) {
        this.wakeUpEngine = engine;
    }

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

    /**
     * Initialize PreProcessedRecorder (async).
     * Must be called before any start/stop/register operations.
     * Requires network connection (per SDK docs).
     */
    public void init() {
        executor.execute(() -> {
            try {
                PreProcessedRecorder.INSTANCE.init(context, result -> {
                    if (result == PreProcessedRecorder.INIT_STATE_SUCCESS) {
                        recorderInitialized.set(true);
                        Log.i(TAG, "PreProcessedRecorder initialized successfully");

                        // Auto-start the recorder hardware after successful init
                        startRecorderHardware();
                    } else {
                        recorderInitialized.set(false);
                        Log.e(TAG, "PreProcessedRecorder init failed, state: " + result);
                        notifyError("PreProcessedRecorder init failed: " + result);
                    }
                    return kotlin.Unit.INSTANCE;
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to init PreProcessedRecorder", e);
                notifyError("Init failed: " + e.getMessage());
            }
        });
    }

    /**
     * Start the PreProcessedRecorder hardware (mic array).
     * Called automatically after successful init.
     * The recorder stays running — we switch modes by register/unregister listeners.
     */
    private void startRecorderHardware() {
        if (recorderStarted.get()) {
            Log.d(TAG, "Recorder hardware already started");
            return;
        }
        try {
            boolean success = PreProcessedRecorder.INSTANCE.start();
            if (success) {
                recorderStarted.set(true);
                Log.i(TAG, "PreProcessedRecorder hardware started");
            } else {
                Log.e(TAG, "PreProcessedRecorder.start() returned false");
                notifyError("Failed to start recorder hardware");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting recorder hardware", e);
            notifyError("Start hardware failed: " + e.getMessage());
        }
    }

    /**
     * Stop the PreProcessedRecorder hardware (mic array).
     * Only called during full shutdown (release).
     */
    private void stopRecorderHardware() {
        if (!recorderStarted.get()) return;
        try {
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

    /**
     * Start receiving FOR_WAKEUP audio and feeding it to the wake engine.
     * Called when entering IDLE state (wake word listening mode).
     */
    public void startWakeupListening() {
        if (!recorderInitialized.get() || !recorderStarted.get()) {
            Log.w(TAG, "Cannot start wakeup listening: recorder not ready");
            return;
        }
        if (wakeupListening.get()) {
            Log.d(TAG, "Wakeup listening already active");
            return;
        }

        try {
            PreProcessedRecorder.INSTANCE.registerRecordListener(
                wakeupRecordListener, AudioRecorder.Type.FOR_WAKEUP);
            wakeupListening.set(true);
            Log.i(TAG, "Wakeup listening started (FOR_WAKEUP registered)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register FOR_WAKEUP listener", e);
            notifyError("Start wakeup listening failed: " + e.getMessage());
        }
    }

    /**
     * Stop receiving FOR_WAKEUP audio.
     * Called when transitioning from IDLE to RECORDING (wake detection not needed during ASR).
     */
    public void stopWakeupListening() {
        if (!wakeupListening.get()) return;

        try {
            PreProcessedRecorder.INSTANCE.unregisterRecordListener(
                wakeupRecordListener, AudioRecorder.Type.FOR_WAKEUP);
            wakeupListening.set(false);
            Log.i(TAG, "Wakeup listening stopped (FOR_WAKEUP unregistered)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to unregister FOR_WAKEUP listener", e);
        }
    }

    // ==================== ASR Audio Control ====================

    /**
     * Start receiving FOR_ASR audio for speech recognition.
     * Called when entering RECORDING state.
     * Generates a new session ID for this recording session.
     *
     * @return Session ID for this recording session
     */
    public String startAsrRecording() {
        if (!recorderInitialized.get() || !recorderStarted.get()) {
            Log.w(TAG, "Cannot start ASR recording: recorder not ready");
            return currentSessionId;
        }

        currentSessionId = UUID.randomUUID().toString();

        if (asrRecording.get()) {
            Log.d(TAG, "ASR recording already active, session: " + currentSessionId);
            return currentSessionId;
        }

        if (vadDetector != null) {
            vadDetector.reset();
        }

        try {
            PreProcessedRecorder.INSTANCE.registerRecordListener(
                asrRecordListener, AudioRecorder.Type.FOR_ASR);
            asrRecording.set(true);
            Log.i(TAG, "ASR recording started, session: " + currentSessionId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register FOR_ASR listener", e);
            notifyError("Start ASR recording failed: " + e.getMessage());
        }

        return currentSessionId;
    }

    /**
     * Stop receiving FOR_ASR audio.
     * Called when leaving RECORDING state.
     */
    public void stopAsrRecording() {
        if (!asrRecording.get()) return;

        try {
            PreProcessedRecorder.INSTANCE.unregisterRecordListener(
                asrRecordListener, AudioRecorder.Type.FOR_ASR);
            asrRecording.set(false);
            Log.i(TAG, "ASR recording stopped, session: " + currentSessionId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to unregister FOR_ASR listener", e);
        }
    }

    // ==================== State Queries ====================

    public boolean isRecorderInitialized() {
        return recorderInitialized.get();
    }

    public boolean isWakeupListening() {
        return wakeupListening.get();
    }

    public boolean isAsrRecording() {
        return asrRecording.get();
    }

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

    /**
     * Full cleanup: stop all listeners, stop hardware, shutdown executor.
     */
    public void release() {
        // Unregister all listeners first
        stopWakeupListening();
        stopAsrRecording();

        // Stop hardware
        stopRecorderHardware();

        executor.shutdownNow();
        Log.i(TAG, "AudioRecorderManager released");
    }
}
