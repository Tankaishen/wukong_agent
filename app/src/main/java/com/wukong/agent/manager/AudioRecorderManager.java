package com.wukong.agent.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.wukong.agent.factories.RecorderFactory;
import com.wukong.agent.interfaces.IRecorder;
import com.wukong.agent.interfaces.IWakeUpEngine;

/**
 * AudioRecorderManager — facade for audio recording.
 *
 * Delegates all recording operations to an IRecorder implementation
 * selected at construction time via RecorderFactory.
 *
 * This class exists to:
 * - Keep WukongService's API unchanged (AudioListener, InitCallback)
 * - Provide a single entry point for recorder type configuration
 * - Handle cross-implementation concerns (error notification, callback adaptation)
 *
 * Supported recorder types:
 * - "android": AndroidAudioRecorder (android.media.AudioRecord, default)
 * - "ubt":     UbtAudioRecorder (UBT PreProcessedRecorder SDK, 6-mic array)
 */
public class AudioRecorderManager {

    private static final String TAG = "AudioRecorderManager";

    /**
     * Listener for ASR audio data (single-channel, post-VAD).
     * Wake word audio is delivered directly to IWakeUpEngine, not through this listener.
     */
    public interface AudioListener {
        void onAudioData(byte[] pcmData);           // 单声道 PCM 数据，可用于 ASR 发送
        void onVadSpeechStart();                    // 检测到用户开始说话
        void onVadSpeechEnd();                      // 检测到用户停止说话
        void onRecordingError(String errorMessage);  // 录音发生错误
    }

    public interface InitCallback {
        void onInitComplete();
        /** Called when the recorder hardware is successfully started and ready for audio. */
        void onRecorderReady();
    }

    private final IRecorder recorder;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private InitCallback externalInitCallback;

    // Bridge: adapt IRecorder callbacks to AudioRecorderManager's listener types
    private final IRecorder.AudioListener internalAsrListener = new IRecorder.AudioListener() {
        @Override
        public void onAudioData(byte[] pcmData) {
            if (externalAsrListener != null) {
                externalAsrListener.onAudioData(pcmData);
            }
        }

        @Override
        public void onVadSpeechStart() {
            if (externalAsrListener != null) {
                externalAsrListener.onVadSpeechStart();
            }
        }

        @Override
        public void onVadSpeechEnd() {
            if (externalAsrListener != null) {
                externalAsrListener.onVadSpeechEnd();
            }
        }

        @Override
        public void onRecordingError(String errorMessage) {
            if (externalAsrListener != null) {
                externalAsrListener.onRecordingError(errorMessage);
            }
        }
    };

    private AudioListener externalAsrListener;

    // Bridge: adapt IRecorder.InitCallback to AudioRecorderManager.InitCallback
    private final IRecorder.InitCallback internalInitCallback = new IRecorder.InitCallback() {
        @Override
        public void onInitComplete() {
            if (externalInitCallback != null) {
                externalInitCallback.onInitComplete();
            }
        }

        @Override
        public void onRecorderReady() {
            if (externalInitCallback != null) {
                externalInitCallback.onRecorderReady();
            }
        }
    };

    /**
     * Create AudioRecorderManager with the specified recorder type.
     *
     * @param context      Application context
     * @param recorderType "android" or "ubt"
     */
    public AudioRecorderManager(Context context, String recorderType) {
        Log.i(TAG, "AudioRecorderManager construct! recorderType=" + recorderType);
        this.context = context.getApplicationContext();
        recorder = RecorderFactory.create(recorderType, context);
    }

    // ==================== Configuration ====================

    public void setAsrListener(AudioListener listener) {
        this.externalAsrListener = listener;
        recorder.setAsrListener(internalAsrListener);
    }

    public void setInitCallback(InitCallback callback) {
        this.externalInitCallback = callback;
    }

    /**
     * Set the wake engine to receive FOR_WAKEUP audio data.
     * Must be called before startWakeupListening().
     */
    public void setWakeUpEngine(IWakeUpEngine engine) {
        recorder.setWakeUpEngine(engine);
    }

    public void setVadParameters(int energyThreshold, int silenceDurationMs) {
        recorder.setVadParameters(energyThreshold, silenceDurationMs);
    }

    // ==================== Lifecycle ====================

    /**
     * Initialize the underlying recorder (async).
     * Must be called before any start/stop operations.
     */
    public void init() {
        recorder.init(context, internalInitCallback);
    }

    // ==================== Wakeup Audio Control ====================

    /**
     * Start receiving wakeup audio and feeding it to the wake engine.
     * Called when entering IDLE state (wake word listening mode).
     */
    public void startWakeupListening() {
        recorder.startWakeupListening();
    }

    /**
     * Stop receiving wakeup audio.
     * Called when transitioning from IDLE to RECORDING.
     */
    public void stopWakeupListening() {
        recorder.stopWakeupListening();
    }

    // ==================== ASR Audio Control ====================

    /**
     * Start receiving ASR audio for speech recognition.
     * Called when entering RECORDING state.
     *
     * @return Session ID for this recording session
     */
    public String startAsrRecording() {
        return recorder.startAsrRecording();
    }

    /**
     * Stop receiving ASR audio.
     * Called when leaving RECORDING state.
     */
    public void stopAsrRecording() {
        recorder.stopAsrRecording();
    }

    // ==================== State Queries ====================

    public boolean isRecorderInitialized() {
        return recorder.isReady();
    }

    /** Returns true if both init and hardware start succeeded. */
    public boolean isRecorderReady() {
        return recorder.isReady();
    }

    public boolean isWakeupListening() {
        return recorder.isWakeupListening();
    }

    public boolean isAsrRecording() {
        return recorder.isAsrRecording();
    }

    public String getCurrentSessionId() {
        return recorder.getCurrentSessionId();
    }

    // ==================== Release ====================

    /**
     * Full cleanup: stop all listeners, release resources.
     */
    public void release() {
        recorder.release();
        Log.i(TAG, "AudioRecorderManager released");
    }
}
