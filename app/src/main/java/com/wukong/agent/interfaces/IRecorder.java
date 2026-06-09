package com.wukong.agent.interfaces;

import android.content.Context;

/**
 * Abstraction for audio recorder implementations.
 *
 * Two implementations exist:
 * - AndroidAudioRecorder: uses android.media.AudioRecord directly
 * - UbtAudioRecorder: uses UBT PreProcessedRecorder SDK (6-mic array)
 *
 * Audio data flow:
 * - Wakeup audio: PCM → IWakeUpEngine.feedAudioData()
 * - ASR audio:    PCM → AudioListener.onAudioData() (+ VAD detection)
 *
 * Mode switching is done via AtomicBoolean flags (not register/unregister),
 * so the recording thread stays alive and just gates data forwarding.
 */
public interface IRecorder {

    /** Callback for ASR audio data and VAD events. */
    interface AudioListener {
        /** Single-channel PCM data ready for ASR transmission. */
        void onAudioData(byte[] pcmData);

        /**
         * Single-channel PCM data with explicit length.
         * Supports pre-allocated buffers where only the first {@code length} bytes are valid.
         * Default implementation delegates to {@link #onAudioData(byte[])} with a trimmed copy.
         */
        default void onAudioData(byte[] pcmData, int length) {
            if (length == pcmData.length) {
                onAudioData(pcmData);
            } else {
                byte[] trimmed = new byte[length];
                System.arraycopy(pcmData, 0, trimmed, 0, length);
                onAudioData(trimmed);
            }
        }

        /** VAD detected user started speaking. */
        void onVadSpeechStart();
        /** VAD detected user stopped speaking. */
        void onVadSpeechEnd();
        /** Recording error occurred. */
        void onRecordingError(String errorMessage);
    }

    /** Callback for recorder initialization lifecycle. */
    interface InitCallback {
        /** Called when init() completes (success or failure). */
        void onInitComplete();
        /** Called when the recorder hardware is ready to capture audio. */
        void onRecorderReady();
    }

    /**
     * Initialize the recorder (async).
     * Must be called before any start/stop operations.
     *
     * @param context  Application context
     * @param callback Initialization lifecycle callback
     */
    void init(Context context, InitCallback callback);

    /** Start forwarding wakeup audio to the wake engine. */
    void startWakeupListening();

    /** Stop forwarding wakeup audio. */
    void stopWakeupListening();

    /**
     * Start ASR recording session.
     * Generates a new session ID for this recording.
     *
     * @return Session ID for this recording session
     */
    String startAsrRecording();

    /** Stop ASR recording session. */
    void stopAsrRecording();

    /** Whether the recorder is initialized and hardware is ready. */
    boolean isReady();

    /** Whether wakeup audio forwarding is active. */
    boolean isWakeupListening();

    /** Whether ASR recording is active. */
    boolean isAsrRecording();

    /** Get the current recording session ID. */
    String getCurrentSessionId();

    /** Set the wake engine to receive wakeup audio. */
    void setWakeUpEngine(IWakeUpEngine engine);

    /** Set the listener for ASR audio data. */
    void setAsrListener(AudioListener listener);

    /**
     * Set VAD parameters.
     *
     * @param energyThreshold   RMS energy threshold for speech detection
     * @param silenceDurationMs Silence duration in ms to trigger speech end
     */
    void setVadParameters(int energyThreshold, int silenceDurationMs);

    /** Release all resources. */
    void release();
}
