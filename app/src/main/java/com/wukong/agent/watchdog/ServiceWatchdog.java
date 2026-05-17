package com.wukong.agent.watchdog;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import com.wukong.agent.service.WukongService;

public class ServiceWatchdog extends JobService {

    private static final String TAG = "ServiceWatchdog";
    private static final int JOB_ID = 1001;
    private static final long INTERVAL_MS = 15 * 60 * 1000; // 15 minutes

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "Watchdog check triggered");

        // Check if service is running - simplified check
        // In production, use a more robust check (e.g., checking a shared flag)
        boolean shouldRestart = true; // Placeholder: implement actual check

        if (shouldRestart) {
            Log.i(TAG, "Ensuring WukongService is running");
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
