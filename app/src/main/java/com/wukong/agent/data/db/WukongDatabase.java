package com.wukong.agent.data.db;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import com.wukong.agent.data.dao.ChatHistoryDao;
import com.wukong.agent.data.dao.WakeUpLogDao;
import com.wukong.agent.data.dao.ActionDao;
import com.wukong.agent.data.entity.ChatHistoryEntity;
import com.wukong.agent.data.entity.WakeUpLogEntity;
import com.wukong.agent.data.entity.ActionEntity;

import java.util.concurrent.CountDownLatch;

@Database(
    entities = {
        ChatHistoryEntity.class,
        WakeUpLogEntity.class,
        ActionEntity.class
    },
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters.class)
public abstract class WukongDatabase extends RoomDatabase {

    private static volatile WukongDatabase INSTANCE;

    public abstract ChatHistoryDao chatHistoryDao();
    public abstract WakeUpLogDao wakeUpLogDao();
    public abstract ActionDao actionDao();

    public static WukongDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (WukongDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                        context.getApplicationContext(),
                        WukongDatabase.class,
                        "wukong_agent_db"
                    )
                    .fallbackToDestructiveMigration()
                    .build();
                }
            }
        }
        return INSTANCE;
    }

    public static WukongDatabase getInstance(Context context, CountDownLatch initLatch) {
        if (INSTANCE == null) {
            synchronized (WukongDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    WukongDatabase.class,
                                    "wukong_agent_db"
                            )
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
