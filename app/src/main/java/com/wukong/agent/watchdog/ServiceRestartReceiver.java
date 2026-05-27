package com.wukong.agent.watchdog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.wukong.agent.service.WukongService;

/*
* 监听特定的自定义广播事件，收到后自动重启 WukongService 后台服务
* */
public class ServiceRestartReceiver extends BroadcastReceiver {

    private static final String TAG = "ServiceRestartReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if ("com.wukong.agent.RESTART_SERVICE".equals(action)) {
            // Respect user stop intent — if user explicitly stopped the service,
            // do not restart it via broadcast either
            if (WukongService.isUserStopped(context)) {
                Log.i(TAG, "User stopped the service — not restarting via broadcast");
                return;
            }
            Log.i(TAG, "Received restart request, starting WukongService");
//            handler.postDelayed(() -> {
//                if (shouldReconnect && !isConnected) {
//                    WukongService.start(context);
//                    Log.i(TAG, "Attempting to reconnect...");
//                }
//            }, delay);
            WukongService.start(context);
        }
    }
}
