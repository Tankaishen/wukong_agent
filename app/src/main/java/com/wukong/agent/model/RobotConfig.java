package com.wukong.agent.model;

import android.util.Log;

import com.wukong.agent.manager.ConfigManager;

import java.util.Collections;
import java.util.Map;

public class RobotConfig {

    private static final String TAG = "RobotConfig";
    private String wsServerUrl;
    private boolean wakeWukongEnabled;
    private boolean wakeNihaoEnabled;
    private int ncmWukong;
    private int ncmNihao;
    private int ttsVolume;
    private int vadSilenceDurationMs;
    private int vadEnergyThreshold;
    private int recordingTimeoutMs;
    private int processingTimeoutMs;
    private int playingTimeoutMs;
    private String llmModelName;

    // 新增
    // "aikit" | "picovoice" | "baidu"
    private String wakeEngineType;
    private Map<String, String> wakeEngineCredentials; // 当前引擎的凭证

    public RobotConfig() {
        // Defaults
//        this.wsServerUrl = "wss://localhost:8080/ws";
        this.wsServerUrl = "ws://222.201.189.87:8080";
        this.wakeWukongEnabled = true;
        this.wakeNihaoEnabled = true;
        this.ncmWukong = 1200;
        this.ncmNihao = 1450;
        this.ttsVolume = 80;
        this.vadSilenceDurationMs = 5000;
        this.vadEnergyThreshold = 500;
        this.recordingTimeoutMs = 30000;
        this.processingTimeoutMs = 10000;
        this.playingTimeoutMs = 60000;
        this.llmModelName = "";
        this.wakeEngineType = "aikit";
        this.wakeEngineCredentials = Collections.emptyMap();
    }

    // Getters and Setters
    public String getWsServerUrl() { return wsServerUrl; }
    public void setWsServerUrl(String url) { this.wsServerUrl = url; }

    public boolean isWakeWukongEnabled() { return wakeWukongEnabled; }
    public void setWakeWukongEnabled(boolean enabled) { this.wakeWukongEnabled = enabled; }

    public boolean isWakeNihaoEnabled() { return wakeNihaoEnabled; }
    public void setWakeNihaoEnabled(boolean enabled) { this.wakeNihaoEnabled = enabled; }

    public int getNcmWukong() { return ncmWukong; }
    public void setNcmWukong(int ncm) { this.ncmWukong = ncm; }

    public int getNcmNihao() { return ncmNihao; }
    public void setNcmNihao(int ncm) { this.ncmNihao = ncm; }

    public int getTtsVolume() { return ttsVolume; }
    public void setTtsVolume(int volume) { this.ttsVolume = volume; }

    public int getVadSilenceDurationMs() { return vadSilenceDurationMs; }
    public void setVadSilenceDurationMs(int ms) { this.vadSilenceDurationMs = ms; }

    public int getVadEnergyThreshold() { return vadEnergyThreshold; }
    public void setVadEnergyThreshold(int threshold) { this.vadEnergyThreshold = threshold; }

    public int getRecordingTimeoutMs() { return recordingTimeoutMs; }
    public void setRecordingTimeoutMs(int ms) { this.recordingTimeoutMs = ms; Log.d(TAG, "RecordingTimeoutMs set:" + ms);}

    public int getProcessingTimeoutMs() { return processingTimeoutMs; }
    public void setProcessingTimeoutMs(int ms) { this.processingTimeoutMs = ms; }

    public int getPlayingTimeoutMs() { return playingTimeoutMs; }
    public void setPlayingTimeoutMs(int ms) { this.playingTimeoutMs = ms; }

    public String getLlmModelName() { return llmModelName; }
    public void setLlmModelName(String name) { this.llmModelName = name; }

    public String getWakeEngineType() { return wakeEngineType; }
    public void setWakeEngineType(String type) { this.wakeEngineType = type; }

    public Map<String, String> getWakeEngineCredentials() { return wakeEngineCredentials; }
    public void setWakeEngineCredentials(Map<String, String> credentials) { this.wakeEngineCredentials = credentials; }
}
