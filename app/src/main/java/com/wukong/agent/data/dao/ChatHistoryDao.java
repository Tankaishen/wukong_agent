package com.wukong.agent.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.wukong.agent.data.entity.ChatHistoryEntity;
import java.util.List;

@Dao
public interface ChatHistoryDao {

    @Insert
    long insert(ChatHistoryEntity chatHistory);

    @Query("SELECT * FROM chat_history ORDER BY timestamp DESC")
    LiveData<List<ChatHistoryEntity>> getAll();

    @Query("SELECT * FROM chat_history WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    LiveData<List<ChatHistoryEntity>> getBySessionId(String sessionId);

    @Query("SELECT * FROM chat_history ORDER BY timestamp DESC LIMIT :limit")
    List<ChatHistoryEntity> getRecent(int limit);

    @Query("DELETE FROM chat_history WHERE timestamp < :beforeTimestamp")
    int deleteOlderThan(long beforeTimestamp);

    @Query("DELETE FROM chat_history")
    void deleteAll();
}
