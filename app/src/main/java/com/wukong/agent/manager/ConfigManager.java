package com.wukong.agent.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;
import com.wukong.agent.model.RobotConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ConfigManager {

    private static volatile ConfigManager INSTANCE;

    private static final String TAG = "ConfigManager";
    private static final String WAKE_ENGINE_CONFIG = "wake_engine.properties";

    private final Context context;
    private final SharedPreferences prefs;
    private final MutableLiveData<RobotConfig> configLiveData = new MutableLiveData<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private RobotConfig currentConfig;

    private ConfigManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = PreferenceManager.getDefaultSharedPreferences(this.context);
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

        // From SharedPreferences (user-adjustable settings)
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

        // From assets/wake_engine.properties (engine type + credentials)
        loadWakeEngineConfig(config);

        return config;
    }

    /**
     * Load wake engine type and credentials from assets/wake_engine.properties.
     * Only loads the credentials for the currently selected engine type.
     *
     * File format:
     *   wake.engine=aikit
     *   aikit.appId=xxx
     *   aikit.apiKey=xxx
     *   aikit.apiSecret=xxx
     *   picovoice.accessKey=xxx
     *   baidu.appId=xxx
     */
    private void loadWakeEngineConfig(RobotConfig config) {
        try (InputStream is = context.getAssets().open(WAKE_ENGINE_CONFIG)) {
            Properties props = new Properties();
            props.load(is);

            String engineType = props.getProperty("wake.engine", "aikit").trim();
            config.setWakeEngineType(engineType);

            // Only load credentials for the selected engine
            Map<String, String> credentials = new HashMap<>();
            String prefix = engineType + ".";
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith(prefix)) {
                    String value = props.getProperty(key, "").trim();
                    // Store even empty values so the engine can report missing credentials
                    credentials.put(key.substring(prefix.length()), value);
                }
            }
            config.setWakeEngineCredentials(credentials);

            Log.i(TAG, "Wake engine config loaded: type=" + engineType
                    + ", credentials keys=" + credentials.keySet());

        } catch (IOException e) {
            Log.e(TAG, "Failed to load wake engine config from " + WAKE_ENGINE_CONFIG, e);
            // Keep defaults (aikit with empty credentials)
        }
    }

    // ==================== Convenience Getters ====================

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
    public String getWakeEngineType() { return currentConfig.getWakeEngineType(); }
    public Map<String, String> getWakeEngineCredentials() { return currentConfig.getWakeEngineCredentials(); }

    public void release() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
    }
}
