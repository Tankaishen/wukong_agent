package com.wukong.agent.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import com.wukong.agent.data.entity.ActionEntity;
import java.util.List;

@Dao
public interface ActionDao {

    @Insert
    long insert(ActionEntity action);

    @Update
    void update(ActionEntity action);

    @Query("SELECT * FROM action_cache WHERE executed = 0 ORDER BY timestamp ASC")
    LiveData<List<ActionEntity>> getPendingActions();

    @Query("SELECT * FROM action_cache ORDER BY timestamp DESC")
    LiveData<List<ActionEntity>> getAll();

    @Query("SELECT * FROM action_cache WHERE executed = 0 ORDER BY timestamp ASC LIMIT :limit")
    List<ActionEntity> getPendingActionsSync(int limit);

    @Query("DELETE FROM action_cache WHERE executed = 1 AND timestamp < :beforeTimestamp")
    int deleteExecutedOlderThan(long beforeTimestamp);

    @Query("DELETE FROM action_cache")
    void deleteAll();
}
