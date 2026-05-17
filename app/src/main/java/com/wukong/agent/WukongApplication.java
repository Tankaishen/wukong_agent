package com.wukong.agent;

import android.app.Application;
import android.util.Log;
import com.wukong.agent.watchdog.ServiceWatchdog;

public class WukongApplication extends Application {

    private static final String TAG = "WukongApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "WukongApplication onCreate");

        // Schedule watchdog to ensure service stays alive
        ServiceWatchdog.scheduleWatchdog(this);
    }
}
