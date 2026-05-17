package com.wukong.agent.data.repository;

import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import com.wukong.agent.data.dao.WakeUpLogDao;
import com.wukong.agent.data.entity.WakeUpLogEntity;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WakeUpLogRepository {

    private final WakeUpLogDao dao;
    private final ExecutorService executor;

    public WakeUpLogRepository(WakeUpLogDao dao) {
        this.dao = dao;
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void insert(WakeUpLogEntity entity) {
        executor.execute(() -> dao.insert(entity));
    }

    public LiveData<List<WakeUpLogEntity>> getAll() {
        return dao.getAll();
    }

    public void deleteOlderThan(long beforeTimestamp) {
        executor.execute(() -> dao.deleteOlderThan(beforeTimestamp));
    }

    public void deleteAll() {
        executor.execute(dao::deleteAll);
    }
}
