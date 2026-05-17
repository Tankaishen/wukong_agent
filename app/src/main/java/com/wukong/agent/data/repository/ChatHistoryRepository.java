package com.wukong.agent.data.repository;

import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import com.wukong.agent.data.dao.ChatHistoryDao;
import com.wukong.agent.data.entity.ChatHistoryEntity;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatHistoryRepository {

    private final ChatHistoryDao dao;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public ChatHistoryRepository(ChatHistoryDao dao) {
        this.dao = dao;
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public long insert(ChatHistoryEntity entity) {
        // Room auto-generates ID on insert, synchronous for simplicity
        return dao.insert(entity);
    }

    public void insertAsync(ChatHistoryEntity entity, final Callback<Long> callback) {
        executor.execute(() -> {
            long id = dao.insert(entity);
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(id));
            }
        });
    }

    public LiveData<List<ChatHistoryEntity>> getAll() {
        return dao.getAll();
    }

    public LiveData<List<ChatHistoryEntity>> getBySessionId(String sessionId) {
        return dao.getBySessionId(sessionId);
    }

    public void deleteOlderThan(long beforeTimestamp) {
        executor.execute(() -> dao.deleteOlderThan(beforeTimestamp));
    }

    public void deleteAll() {
        executor.execute(dao::deleteAll);
    }

    public interface Callback<T> {
        void onResult(T result);
    }
}
