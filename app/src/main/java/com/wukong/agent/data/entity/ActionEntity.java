package com.wukong.agent.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "action_cache")
public class ActionEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String actionType;
    public String params; // JSON string
    public long timestamp;
    public boolean executed;

    public ActionEntity(String actionType, String params, long timestamp, boolean executed) {
        this.actionType = actionType;
        this.params = params;
        this.timestamp = timestamp;
        this.executed = executed;
    }
}
