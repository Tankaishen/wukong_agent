package com.wukong.agent.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "wakeup_log")
public class WakeUpLogEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String keyword;
    public int confidence;
    public long timestamp;

    public WakeUpLogEntity(String keyword, int confidence, long timestamp) {
        this.keyword = keyword;
        this.confidence = confidence;
        this.timestamp = timestamp;
    }
}
