package com.wukong.agent.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;
import com.wukong.agent.model.RobotConfig;

public class ConfigManager {

    private static volatile ConfigManager INSTANCE;
    private final SharedPreferences prefs;
    private final MutableLiveData<RobotConfig> configLiveData = new MutableLiveData<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private RobotConfig currentConfig;

    private ConfigManager(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        this.currentConfig = loadConfig();
        this.configLiveData.postValue(currentConfig);

        prefsListener = (sharedPrefs, key) -> {
            RobotConfig newConfig = loadConfig();
            currentConfig = newConfig;
            configLiveData.postValue(newConfig);
        };
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
    }

    public static ConfigManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ConfigManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ConfigManager(context);
                }
            }
        }
        return INSTANCE;
    }

    public LiveData<RobotConfig> getConfigLiveData() {
        return configLiveData;
    }

    public RobotConfig getConfig() {
        return currentConfig;
    }

    private RobotConfig loadConfig() {
        RobotConfig config = new RobotConfig();
        config.setWsServerUrl(prefs.getString("ws_server_url", config.getWsServerUrl()));
        config.setWakeWukongEnabled(prefs.getBoolean("wake_word_wukong_enabled", config.isWakeWukongEnabled()));
        config.setWakeNihaoEnabled(prefs.getBoolean("wake_word_nihao_enabled", config.isWakeNihaoEnabled()));
        config.setNcmWukong(prefs.getInt("wake_word_ncm_wukong", config.getNcmWukong()));
        config.setNcmNihao(prefs.getInt("wake_word_ncm_nihao", config.getNcmNihao()));
        config.setTtsVolume(prefs.getInt("tts_volume", config.getTtsVolume()));
        config.setVadSilenceDurationMs(prefs.getInt("vad_silence_duration_ms", config.getVadSilenceDurationMs()));
        config.setVadEnergyThreshold(prefs.getInt("vad_energy_threshold", config.getVadEnergyThreshold()));
        config.setRecordingTimeoutMs(prefs.getInt("recording_timeout_ms", config.getRecordingTimeoutMs()));
        config.setProcessingTimeoutMs(prefs.getInt("processing_timeout_ms", config.getProcessingTimeoutMs()));
        config.setPlayingTimeoutMs(prefs.getInt("playing_timeout_ms", config.getPlayingTimeoutMs()));
        config.setLlmModelName(prefs.getString("llm_model_name", config.getLlmModelName()));
        return config;
    }

    public String getWsServerUrl() { return currentConfig.getWsServerUrl(); }
    public boolean isWakeWukongEnabled() { return currentConfig.isWakeWukongEnabled(); }
    public boolean isWakeNihaoEnabled() { return currentConfig.isWakeNihaoEnabled(); }
    public int getNcmWukong() { return currentConfig.getNcmWukong(); }
    public int getNcmNihao() { return currentConfig.getNcmNihao(); }
    public int getTtsVolume() { return currentConfig.getTtsVolume(); }
    public int getVadSilenceDurationMs() { return currentConfig.getVadSilenceDurationMs(); }
    public int getVadEnergyThreshold() { return currentConfig.getVadEnergyThreshold(); }
    public int getRecordingTimeoutMs() { return currentConfig.getRecordingTimeoutMs(); }
    public int getProcessingTimeoutMs() { return currentConfig.getProcessingTimeoutMs(); }
    public int getPlayingTimeoutMs() { return currentConfig.getPlayingTimeoutMs(); }
    public String getLlmModelName() { return currentConfig.getLlmModelName(); }

    public void release() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
    }
}
