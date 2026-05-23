package com.wukong.agent.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import com.wukong.agent.R;
import com.wukong.agent.service.WukongService;
import com.wukong.agent.statemachine.BusinessState;

/**
 * Settings screen for wukong_agent.
 *
 * Provides:
 * - Service status display (running/stopped + current business state)
 * - Start/Stop service buttons for debug convenience
 * - All runtime configuration (WebSocket URL, wake word, audio, timeouts)
 *
 * The status display auto-refreshes every second while the fragment is visible,
 * so you can see state changes in real-time during debugging.
 */
public class SettingsFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "SettingsFragment";
    private static final long STATUS_REFRESH_INTERVAL_MS = 1000;

    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private Preference statusPref;
    private Preference startPref;
    private Preference stopPref;

    private final Runnable statusRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (isAdded()) {
                updateStatusDisplay();
                statusHandler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS);
            }
        }
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
    }

    @Override
    public void onResume() {
        super.onResume();
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .registerOnSharedPreferenceChangeListener(this);

        // Cache preference references
        statusPref = findPreference(getString(R.string.pref_key_service_status));
        startPref = findPreference(getString(R.string.pref_key_service_start));
        stopPref = findPreference(getString(R.string.pref_key_service_stop));

        // Setup service control buttons
        setupServiceControlButtons();

        // Start periodic status refresh
        updateStatusDisplay();
        statusHandler.postDelayed(statusRefreshRunnable, STATUS_REFRESH_INTERVAL_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .unregisterOnSharedPreferenceChangeListener(this);
        // Stop periodic status refresh
        statusHandler.removeCallbacks(statusRefreshRunnable);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        Log.d(TAG, "Preference changed: " + key);
        // ConfigManager will automatically pick up changes via its LiveData observer
    }

    // ==================== Service Control ====================

    private void setupServiceControlButtons() {
        if (startPref != null) {
            startPref.setOnPreferenceClickListener(preference -> {
                Log.i(TAG, "Starting WukongService via settings");
                WukongService.start(requireContext());
                Toast.makeText(requireContext(),
                        getString(R.string.pref_service_start_toast), Toast.LENGTH_SHORT).show();
                // Delay status update to allow service to start
                statusHandler.postDelayed(this::updateStatusDisplay, 500);
                return true;
            });
        }

        if (stopPref != null) {
            stopPref.setOnPreferenceClickListener(preference -> {
                Log.i(TAG, "Stopping WukongService via settings");
                WukongService.stop(requireContext());
                Toast.makeText(requireContext(),
                        getString(R.string.pref_service_stop_toast), Toast.LENGTH_SHORT).show();
                // Delay status update to allow service to stop
                statusHandler.postDelayed(this::updateStatusDisplay, 500);
                return true;
            });
        }
    }

    // ==================== Status Display ====================

    /**
     * Update the service status display.
     * Shows whether the service is running/stopped, and the current business state.
     *
     * Format: "Running — IDLE" / "Running — RECORDING" / "Stopped"
     */
    private void updateStatusDisplay() {
        if (statusPref == null) return;

        boolean running = WukongService.isRunning();
        String summary;

        if (running) {
            BusinessState state = WukongService.getCurrentState();
            String stateLabel;
            if (state != null) {
                stateLabel = state.name();
            } else {
                stateLabel = "Initializing";
            }
            summary = getString(R.string.pref_service_status_running) + " — " + stateLabel;
        } else {
            summary = getString(R.string.pref_service_status_stopped);
        }

        statusPref.setSummary(summary);
    }
}
