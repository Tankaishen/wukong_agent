package com.wukong.agent.service;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.wukong.agent.R;

/**
 * Transparent Activity for requesting runtime permissions from a Service context.
 * <p>
 * Android requires an Activity to show the system permission dialog.
 * WukongService launches this Activity when RECORD_AUDIO is not granted.
 * After the user grants/denies, this Activity finishes automatically.
 * <p>
 * Flow:
 * 1. Service detects missing permission → start PermissionActivity
 * 2. PermissionActivity requests permission via system dialog
 * 3. On result → notify WukongService if granted → finish()
 */
public class PermissionActivity extends AppCompatActivity {

    private static final String TAG = "PermissionActivity";
    private static final int REQUEST_AUDIO_PERMISSION = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "PermissionActivity created, requesting RECORD_AUDIO");

        // Check if already granted (race condition guard)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "RECORD_AUDIO already granted, finishing");
            notifyServicePermissionResult(true);
            finish();
            return;
        }

        // Request permission — shouldShowRequestPermissionRationale returns false
        // on first ask; we still request directly since this is a voice assistant
        // that fundamentally needs mic access.
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                REQUEST_AUDIO_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;

            if (granted) {
                Log.i(TAG, "RECORD_AUDIO permission granted");
            } else {
                Log.w(TAG, "RECORD_AUDIO permission denied");
                // User denied — we can't record audio, but the service
                // will handle this gracefully in its init retry logic.
            }

            notifyServicePermissionResult(granted);
            finish();
        }
    }

    /**
     * Let WukongService know the permission result so it can
     * proceed with recorder init or log the denial.
     */
    private void notifyServicePermissionResult(boolean granted) {
        // WukongService will check permission again on its side;
        // we just need to trigger it to re-attempt init.
        // The simplest approach: WukongService reads the permission
        // state directly when onRecorderReady / init retry fires.
        // No explicit callback needed — the retry in AndroidAudioRecorder
        // will catch SecurityException if still denied.
    }

    @Override
    public void finish() {
        // Clear activity transition animation so it's invisible
        super.finish();
        overridePendingTransition(0, 0);
    }
}
