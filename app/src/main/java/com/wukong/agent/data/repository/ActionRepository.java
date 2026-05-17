package com.wukong.agent.data.repository;

import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import com.wukong.agent.data.dao.ActionDao;
import com.wukong.agent.data.entity.ActionEntity;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ActionRepository {

    private final ActionDao dao;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public ActionRepository(ActionDao dao) {
        this.dao = dao;
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void insert(ActionEntity entity) {
        executor.execute(() -> dao.insert(entity));
    }

    public void markExecuted(ActionEntity entity) {
        entity.executed = true;
        executor.execute(() -> dao.update(entity));
    }

    public LiveData<List<ActionEntity>> getPendingActions() {
        return dao.getPendingActions();
    }

    public void getPendingActionsSync(Callback<List<ActionEntity>> callback) {
        executor.execute(() -> {
            List<ActionEntity> actions = dao.getPendingActionsSync(50);
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(actions));
            }
        });
    }

    public void deleteExecutedOlderThan(long beforeTimestamp) {
        executor.execute(() -> dao.deleteExecutedOlderThan(beforeTimestamp));
    }

    public interface Callback<T> {
        void onResult(T result);
    }
}
