package com.wukong.agent.statemachine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.wukong.agent.model.RobotConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class BusinessStateMachine {

    private static final String TAG = "BusinessStateMachine";

    private BusinessState currentState = BusinessState.IDLE;
    private final List<StateChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Runnable> timeoutRunnables = new ArrayList<>();

    // Timeout configuration (defaults, can be overridden by ConfigManager)
    private long recordingTimeoutMs = 30000;
    private long processingTimeoutMs = 10000;
    private long playingTimeoutMs = 60000;

    public synchronized BusinessState getCurrentState() {
        return currentState;
    }

    public void addListener(StateChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(StateChangeListener listener) {
        listeners.remove(listener);
    }

    public synchronized boolean transitionTo(BusinessState newState) {
        return transitionTo(newState, null);
    }

    public synchronized boolean transitionTo(BusinessState newState, String reason) {
        if (currentState != BusinessState.IDLE && !isValidTransition(currentState, newState)) {
            Log.w(TAG, "Invalid transition: " + currentState + " -> " + newState);
            return false;
        }

        BusinessState oldState = currentState;
        cancelAllTimeouts();

        Log.i(TAG, "State transition: " + oldState + " -> " + newState
                + (reason != null ? " (" + reason + ")" : " No reason"));

        currentState = newState;
        notifyStateChanged(oldState, newState);
        startTimeoutForState(newState);

        return true;
    }

    public synchronized boolean forceTransitionTo(BusinessState newState, String reason) {
        BusinessState oldState = currentState;
        cancelAllTimeouts();

        Log.w(TAG, "Force transition: " + oldState + " -> " + newState
                + (reason != null ? " (" + reason + ")" : ""));

        currentState = newState;
        notifyStateChanged(oldState, newState);
        startTimeoutForState(newState);

        return true;
    }

    private boolean isValidTransition(BusinessState from, BusinessState to) {
        if (from == to) return false;

        switch (from) {
            case IDLE:
                return to == BusinessState.WAKEUP;
            case WAKEUP:
                return to == BusinessState.RECORDING || to == BusinessState.IDLE;
            case RECORDING:
                return to == BusinessState.PROCESSING || to == BusinessState.IDLE;
            case PROCESSING:
                return to == BusinessState.PLAYING || to == BusinessState.IDLE;
            case PLAYING:
                return to == BusinessState.IDLE || to == BusinessState.RECORDING;
            default:
                return false;
        }
    }

    private void startTimeoutForState(BusinessState state) {
        long timeoutMs = 0;
        switch (state) {
            case RECORDING:
                timeoutMs = recordingTimeoutMs;
                break;
            case PROCESSING:
                timeoutMs = processingTimeoutMs;
                break;
            case PLAYING:
                timeoutMs = playingTimeoutMs;
                break;
            default:
                return; // No timeout for IDLE, WAKEUP
        }

        if (timeoutMs > 0) {
            Runnable timeoutRunnable = () -> {
                synchronized (BusinessStateMachine.this) {
                    if (currentState == state) {
                        Log.w(TAG, "Timeout in state: " + state + ", returning to IDLE");
                        forceTransitionTo(BusinessState.IDLE, "timeout");
                    }
                }
            };
            timeoutRunnables.add(timeoutRunnable);
            handler.postDelayed(timeoutRunnable, timeoutMs);
        }
    }

    private void cancelAllTimeouts() {
        for (Runnable r : timeoutRunnables) {
            handler.removeCallbacks(r);
        }
        timeoutRunnables.clear();
    }

    private void notifyStateChanged(BusinessState oldState, BusinessState newState) {
        for (StateChangeListener listener : listeners) {
            try {
                listener.onStateChanged(oldState, newState);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying listener", e);
            }
        }
    }

    public void notifyError(BusinessState state, String errorMessage) {
        for (StateChangeListener listener : listeners) {
            try {
                listener.onStateError(state, errorMessage);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying error listener", e);
            }
        }
    }

    // Configuration setters
    public void setRecordingTimeoutMs(long ms) { this.recordingTimeoutMs = ms; }
    public void setProcessingTimeoutMs(long ms) { this.processingTimeoutMs = ms; }
    public void setPlayingTimeoutMs(long ms) { this.playingTimeoutMs = ms; }

    public void applyConfigToStateMachine(RobotConfig config) {
        this.setRecordingTimeoutMs(config.getRecordingTimeoutMs());
        this.setProcessingTimeoutMs(config.getProcessingTimeoutMs());
        this.setPlayingTimeoutMs(config.getPlayingTimeoutMs());
    }

    public void cleanup() {
        cancelAllTimeouts();
        listeners.clear();
    }
}
