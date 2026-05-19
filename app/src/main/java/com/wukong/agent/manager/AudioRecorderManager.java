package com.wukong.agent.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;

import com.wukong.agent.util.AudioUtils;
import com.wukong.agent.util.VADDetector;

/**
 * AudioRecorderManager uses the robot's PreProcessedRecorder for audio capture.
 * It provides two types of audio:
 * - FOR_WAKEUP: Single channel for wake word detection
 * - FOR_ASR: 6-channel for speech recognition (we extract channel 0 for ASR)
 */
public class AudioRecorderManager {

    private static final String TAG = "AudioRecorderManager";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNELS = 1;
    private static final int BIT_DEPTH = 16;

    public interface AudioListener {
        void onAudioData(byte[] pcmData); // Single channel PCM data ready for sending
        void onVadSpeechStart();
        void onVadSpeechEnd();
        void onRecordingError(String errorMessage);
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AudioListener listener;
    private VADDetector vadDetector;
    private boolean isRecording = false;
    private String currentSessionId;

    public AudioRecorderManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(AudioListener listener) {
        this.listener = listener;
    }

    public void setVadParameters(int energyThreshold, int silenceDurationMs) {
        this.vadDetector = new VADDetector(energyThreshold, silenceDurationMs);
        this.vadDetector.setListener(new VADDetector.VADListener() {
            @Override
            public void onSpeechStart() {
                handler.post(() -> {
                    if (listener != null) listener.onVadSpeechStart();
                });
            }

            @Override
            public void onSpeechEnd() {
                handler.post(() -> {
                    if (listener != null) listener.onVadSpeechEnd();
                });
            }
        });
    }

    public boolean isRecording() {
        return isRecording;
    }

    /**
     * Initialize PreProcessedRecorder.
     * Must be called before startRecording().
     */
    public void init() {
        executor.execute(() -> {
            try {
                // PreProcessedRecorder.init(context);
                Log.i(TAG, "PreProcessedRecorder initialized");
            } catch (Exception e) {
                Log.e(TAG, "Failed to init PreProcessedRecorder", e);
                notifyError("Init failed: " + e.getMessage());
            }
        });
    }

    /**
     * Start recording audio for ASR.
     * @return Session ID for this recording session
     */
    public String startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording");
            return currentSessionId;
        }

        currentSessionId = UUID.randomUUID().toString();
        isRecording = true;

        if (vadDetector != null) {
            vadDetector.reset();
        }

        executor.execute(() -> {
            try {
                // PreProcessedRecorder.start();
                // PreProcessedRecorder.registerRecordListener(asrListener,
                //     AudioRecorder.Type.FOR_ASR);
                Log.i(TAG, "Recording started, session: " + currentSessionId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start recording", e);
                isRecording = false;
                notifyError("Start recording failed: " + e.getMessage());
            }
        });

        return currentSessionId;
    }

    /**
     * Stop recording audio.
     */
    public void stopRecording() {
        if (!isRecording) return;
        isRecording = false;

        executor.execute(() -> {
            try {
                // PreProcessedRecorder.unregisterRecordListener(asrListener,
                //     AudioRecorder.Type.FOR_ASR);
                // PreProcessedRecorder.stop();
                Log.i(TAG, "Recording stopped, session: " + currentSessionId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop recording", e);
            }
        });
    }

    /**
     * Called by PreProcessedRecorder's FOR_ASR callback.
     * Processes 6-channel audio: extracts channel 0 and runs VAD.
     */
    public void onAsrAudioData(byte[] sixChannelData) {
        if (!isRecording) return;

        // Extract single channel (MIC1 - channel 0)
        byte[] singleChannel = AudioUtils.extractChannel(sixChannelData, 0);

        // Run VAD
        if (vadDetector != null) {
            // Estimate chunk duration: each sample = 2 bytes, sample rate = 16000
            long chunkDurationMs = (singleChannel.length / 2) * 1000L / SAMPLE_RATE;
            vadDetector.processAudioChunk(singleChannel, chunkDurationMs);
        }

        // Notify listener with single-channel data
        if (listener != null) {
            listener.onAudioData(singleChannel);
        }
    }

    /**
     * Called by PreProcessedRecorder's FOR_WAKEUP callback.
     * Returns single-channel audio data for wake word detection.
     */
    public void onWakeUpAudioData(byte[] monoData) {
        // This data goes directly to IWakeUpEngine
        // The wake engine will be fed from the service layer
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }

    private void notifyError(String message) {
        handler.post(() -> {
            if (listener != null) {
                listener.onRecordingError(message);
            }
        });
    }

    public void release() {
        stopRecording();
        executor.shutdownNow();
        // PreProcessedRecorder.stop();
    }
}
