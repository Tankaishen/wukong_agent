package com.wukong.agent;

import android.app.Application;
import android.util.Log;

import com.ubtrobot.api.WkSdk;
import com.wukong.agent.service.WukongService;
import com.wukong.agent.watchdog.ServiceWatchdog;

public class WukongApplication extends Application {

    private static final String TAG = "WukongApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "WukongApplication onCreate");
        WkSdk.INSTANCE.init(this);
        // Schedule watchdog to ensure service stays alive
//        if(!WukongService.isRunning()){
//            WukongService.start(this);
//        }
//        ServiceWatchdog.scheduleWatchdog(this);
    }
}
