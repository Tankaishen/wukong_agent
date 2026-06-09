package com.wukong.agent.util;

import android.util.Log;

public class VADDetector {

    private static final String TAG = "VADDetector";

    private int energyThreshold;
    private long silenceDurationMs;
    private boolean isSpeaking;

    private long silenceStartTime = 0;
    private long speechStartTime = 0;

    public interface VADListener {
        void onSpeechStart();
        void onSpeechEnd();
    }

    private VADListener listener;

    public VADDetector(int energyThreshold, long silenceDurationMs) {
        this.energyThreshold = energyThreshold; // 目前为500
        this.silenceDurationMs = silenceDurationMs; // 目前为5000,在RobotConfig.java中设置
    }

    public void setListener(VADListener listener) {
        this.listener = listener;
    }

    public void setEnergyThreshold(int threshold) {
        this.energyThreshold = threshold;
    }

    public void setSilenceDurationMs(long ms) {
        this.silenceDurationMs = ms;
    }

    /**
     * Process a chunk of PCM audio data for VAD detection.
     * @param pcmData PCM 16bit audio chunk
     * @param chunkDurationMs Duration of this chunk in milliseconds
     */
    public void processAudioChunk(byte[] pcmData, long chunkDurationMs) {
        processAudioChunk(pcmData, pcmData.length, chunkDurationMs);
    }

    /**
     * Process a chunk of PCM audio data for VAD detection with explicit length.
     * Supports pre-allocated buffers where only the first {@code length} bytes are valid.
     *
     * @param pcmData PCM 16bit audio buffer (may be larger than valid data)
     * @param length  Number of valid bytes in pcmData
     * @param chunkDurationMs Duration of this chunk in milliseconds
     */
    public void processAudioChunk(byte[] pcmData, int length, long chunkDurationMs) {
        double rmsEnergy = AudioUtils.calculateRmsEnergy(pcmData, length);
        long now = System.currentTimeMillis();

        if (rmsEnergy > energyThreshold) {
            // Sound detected
            if (!isSpeaking) {
                isSpeaking = true;
                speechStartTime = now;
                silenceStartTime = 0;
                if (listener != null) {
                    listener.onSpeechStart();
                }
                Log.d(TAG, "Speech started, energy: " + (int) rmsEnergy);
            }
            silenceStartTime = 0; // Reset silence timer
        } else {
            // Silence detected
            if (isSpeaking) {
                if (silenceStartTime == 0) {
                    silenceStartTime = now;
                }
                long silenceDuration = now - silenceStartTime;
                if (silenceDuration >= silenceDurationMs) {
                    isSpeaking = false;
                    if (listener != null) {
                        listener.onSpeechEnd();
                    }
                    Log.d(TAG, "Speech ended after " + silenceDuration + "ms silence");
                    silenceStartTime = 0;
                }
            }
        }
    }

    public boolean isSpeaking() {
        return isSpeaking;
    }

    public void reset() {
        isSpeaking = false;
        silenceStartTime = 0;
        speechStartTime = 0;
    }
}
