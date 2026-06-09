package com.wukong.agent.interfaces;

import android.content.Context;
import java.util.Map;

public interface IWakeUpEngine {

    interface WakeUpListener {
        void onWakeUp(String keyword, int confidence);
        void onWakeUpError(String errorMessage);
    }

    interface InitCallback {
        void onInitComplete();
    }

    /** Callback for async initialization result */
    /**
     * Initialize engine with credentials and async callback.
     * The callback is invoked on the main thread after init completes (success or failure).
     */
    void init(Context context, Map<String, String> credentials);
//    void init(Context context, Map<String, String> credentials, InitCallback callback);
    /** Set result callback */
    void setListener(WakeUpListener listener);
    void setInitCallback(InitCallback callback);

    /** Start listening for wake words */
    void startListening();

    /** Stop listening */
    void stopListening();

    /** Feed audio data (full array) */
    void feedAudioData(byte[] pcmData);

    /**
     * Feed audio data with explicit length.
     * The caller may reuse the buffer across calls — implementations must copy
     * the data if they consume it asynchronously.
     *
     * @param pcmData audio buffer (may be reused by caller after return)
     * @param length  number of valid bytes in pcmData
     */
    void feedAudioData(byte[] pcmData, int length);

    /** Dynamically update wake word config */
    void updateWakeWordConfig(boolean wukongEnabled, boolean nihaoEnabled,
                              int ncmWukong, int ncmNihao);

    /** Release resources */
    void release();

    /** Whether engine has been initialized */
    boolean isInitialized();

    /** Whether engine is currently listening */
    boolean isListening();


}
