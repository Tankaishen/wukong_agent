package com.wukong.agent.watchdog;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import com.wukong.agent.service.WukongService;

/*
* 利用 Android JobScheduler 每 15 分钟触发一次，检查并确保 WukongService 存活的看门狗。
* */
public class ServiceWatchdog extends JobService {

    private static final String TAG = "ServiceWatchdog"; // 日志标签常量
    private static final int JOB_ID = 1001;
    private static final long INTERVAL_MS = 15 * 60 * 1000; // 15 minutes

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "Watchdog check triggered");

        // Real service alive check:
        // 1. isRunning() — static volatile flag, true if service is alive in this process.
        //    If process was killed, flag resets to false (default), so watchdog detects it.
        // 2. isUserStopped() — persisted flag in SharedPreferences, true only if user
        //    explicitly called WukongService.stop(). Cleared when service starts again.
        boolean serviceAlive = WukongService.isRunning();
        boolean userStopped = WukongService.isUserStopped(this);

        if (serviceAlive) {
            Log.i(TAG, "WukongService is running — no action needed");
        } else if (userStopped) {
            Log.i(TAG, "WukongService was stopped by user — respecting user intent, not restarting");
        } else {
            // Service not running and user didn't stop it → must have been killed by system
            Log.i(TAG, "WukongService is not running and not user-stopped — restarting");
            WukongService.start(this);
        }

        // Schedule next check
        scheduleWatchdog(this);
        return false; // No background work
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // Reschedule
    }

    /**
     * Schedule periodic watchdog check.
     */
    public static void scheduleWatchdog(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        JobInfo jobInfo = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, ServiceWatchdog.class))
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build();

        int result = scheduler.schedule(jobInfo);
        if (result == JobScheduler.RESULT_SUCCESS) {
            Log.i(TAG, "Watchdog scheduled successfully");
        } else {
            Log.e(TAG, "Failed to schedule watchdog");
        }
    }

    /**
     * Cancel watchdog.
     */
    public static void cancelWatchdog(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            scheduler.cancel(JOB_ID);
        }
    }
}
