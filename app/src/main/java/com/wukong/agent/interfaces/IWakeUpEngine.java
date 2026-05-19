package com.wukong.agent.interfaces;

import android.content.Context;
import java.util.Map;

public interface IWakeUpEngine {

    interface WakeUpListener {
        void onWakeUp(String keyword, int confidence);
        void onWakeUpError(String errorMessage);
    }

    /** 用凭证初始化引擎 */
    void init(Context context, Map<String, String> credentials);

    /** 设置结果回调 */
    void setListener(WakeUpListener listener);

    /** 开始监听唤醒词 */
    void startListening();

    /** 停止监听 */
    void stopListening();

    /** 送入音频数据 */
    void feedAudioData(byte[] pcmData);

    /** 动态更新唤醒词配置 */
    void updateWakeWordConfig(boolean wukongEnabled, boolean nihaoEnabled,
                              int ncmWukong, int ncmNihao);

    /** 释放资源 */
    void release();

    /** 引擎是否已初始化 */
    boolean isInitialized();

    /** 引擎是否正在监听 */
    boolean isListening();
}
