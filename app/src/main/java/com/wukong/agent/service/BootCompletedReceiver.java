package com.wukong.agent.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "BootCompletedReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Received broadcast: " + action);
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
//                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                ||"com.wukong.agent.TEST_BOOT".equals(action)) {
            Log.i(TAG, "Boot completed or unlocked, starting WukongService");
            WukongService.start(context);
        }
    }
}
