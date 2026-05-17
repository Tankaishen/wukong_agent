package com.wukong.agent.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.wukong.agent.data.entity.WakeUpLogEntity;
import java.util.List;

@Dao
public interface WakeUpLogDao {

    @Insert
    long insert(WakeUpLogEntity wakeUpLog);

    @Query("SELECT * FROM wakeup_log ORDER BY timestamp DESC")
    LiveData<List<WakeUpLogEntity>> getAll();

    @Query("SELECT * FROM wakeup_log ORDER BY timestamp DESC LIMIT :limit")
    List<WakeUpLogEntity> getRecent(int limit);

    @Query("DELETE FROM wakeup_log WHERE timestamp < :beforeTimestamp")
    int deleteOlderThan(long beforeTimestamp);

    @Query("DELETE FROM wakeup_log")
    void deleteAll();
}
