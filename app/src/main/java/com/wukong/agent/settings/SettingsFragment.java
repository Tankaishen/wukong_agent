package com.wukong.agent.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import com.wukong.agent.R;
import com.wukong.agent.manager.ConfigManager;
import com.wukong.agent.service.WukongService;
import com.wukong.agent.statemachine.BusinessState;

public class SettingsFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "SettingsFragment";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
    }

    @Override
    public void onResume() {
        super.onResume();
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .registerOnSharedPreferenceChangeListener(this);
        updateStatusPreference();
    }

    @Override
    public void onPause() {
        super.onPause();
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        Log.d(TAG, "Preference changed: " + key);
        // ConfigManager will automatically pick up changes via its LiveData observer
        updateStatusPreference();
    }

    private void updateStatusPreference() {
        Preference statusPref = findPreference(getString(R.string.pref_key_service_status));
        if (statusPref != null) {
            // Show current business state
            ConfigManager configManager = ConfigManager.getInstance(requireContext());
            statusPref.setSummary("Service running"); // In production, observe actual state
        }
    }
}
