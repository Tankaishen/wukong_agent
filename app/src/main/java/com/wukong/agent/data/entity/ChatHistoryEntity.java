package com.wukong.agent.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Index;
import androidx.annotation.NonNull;

@Entity(tableName = "chat_history", indices = {@Index(value = {"sessionId"})})
public class ChatHistoryEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String sessionId;

    @NonNull
    public String role; // "user" or "assistant"

    @NonNull
    public String content;

    public long timestamp;

    public ChatHistoryEntity(@NonNull String sessionId, @NonNull String role,
                             @NonNull String content, long timestamp) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }
}
