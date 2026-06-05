package com.wukong.agent.model;

import com.google.gson.annotations.SerializedName;

public class WebSocketMessage {

    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_TTS = "tts";
    public static final String TYPE_PING = "ping";
    public static final String TYPE_PONG = "pong";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_CANCEL = "cancel";

    @SerializedName("type")
    private String type;

    @SerializedName("session")
    private String sessionId;

    @SerializedName("audio")
    private String audioBase64;

    @SerializedName("text")
    private String text;

    @SerializedName("action")
    private String action;

    @SerializedName("is_final")
    private boolean isFinal;

    @SerializedName("error")
    private String error;

    // Chat message constructor
    public static WebSocketMessage createChat(String sessionId, String audioBase64, boolean isFinal) {
        WebSocketMessage msg = new WebSocketMessage();
        msg.type = TYPE_CHAT;
        msg.sessionId = sessionId;
        msg.audioBase64 = audioBase64;
        msg.isFinal = isFinal;
        return msg;
    }

    // Cancel message — notify server to stop generating for a session
    public static WebSocketMessage createCancel(String sessionId) {
        WebSocketMessage msg = new WebSocketMessage();
        msg.type = TYPE_CANCEL;
        msg.sessionId = sessionId;
        return msg;
    }

    // Ping message
    public static WebSocketMessage createPing() {
        WebSocketMessage msg = new WebSocketMessage();
        msg.type = TYPE_PING;
        return msg;
    }

    // Getters
    public String getType() { return type; }
    public String getSessionId() { return sessionId; }
    public String getAudioBase64() { return audioBase64; }
    public String getText() { return text; }
    public String getAction() { return action; }
    public boolean isFinal() { return isFinal; }
    public String getError() { return error; }

    public boolean isTts() { return TYPE_TTS.equals(type); }
    public boolean isChat() { return TYPE_CHAT.equals(type); }
    public boolean isPong() { return TYPE_PONG.equals(type); }
    public boolean isError() { return TYPE_ERROR.equals(type); }
    public boolean isCancel() { return TYPE_CANCEL.equals(type); }
}
