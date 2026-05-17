package com.wukong.agent.watchdog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.wukong.agent.service.WukongService;

public class ServiceRestartReceiver extends BroadcastReceiver {

    private static final String TAG = "ServiceRestartReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if ("com.wukong.agent.RESTART_SERVICE".equals(action)) {
            Log.i(TAG, "Received restart request, starting WukongService");
            WukongService.start(context);
        }
    }
}
