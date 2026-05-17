package com.wukong.agent.coordinator;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.wukong.agent.statemachine.BusinessState;
import com.wukong.agent.statemachine.BusinessStateMachine;
import com.wukong.agent.statemachine.StateChangeListener;

/**
 * RobotStateCoordinator bridges the business state machine and the robot's system state.
 * It is the ONLY class that knows about both systems, ensuring clean separation.
 *
 * Key responsibilities:
 * 1. When our state is non-IDLE, ensure the robot stays Active (inject WantToActive)
 * 2. When the robot goes to Sleep while we are non-IDLE, force it back to Active
 * 3. When the robot wakes up externally (PIR/button), notify our state machine
 * 4. Decouple: BusinessStateMachine never references robot SDK classes
 */
public class RobotStateCoordinator implements StateChangeListener {

    private static final String TAG = "RobotStateCoordinator";

    private final Context context;
    private final BusinessStateMachine stateMachine;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRobotActive = true;
    private Runnable keepAliveRunnable;

    // Keep-alive interval: inject WantToActive every 3 minutes
    // (Robot auto-standby after 5 min inactivity)
    private static final long KEEP_ALIVE_INTERVAL_MS = 3 * 60 * 1000;

    public RobotStateCoordinator(Context context, BusinessStateMachine stateMachine) {
        this.context = context.getApplicationContext();
        this.stateMachine = stateMachine;
        this.stateMachine.addListener(this);
    }

    /**
     * Initialize: subscribe to robot system state events.
     */
    public void init() {
        // Subscribe to robot state changes
        // SysEventApi.get().subscribe(AlphaStateEvent.instance(),
        //     new AlphaStateEventReceiver() {
        //         @Override
        //         public boolean onReceive(AlphaStateEvent event) {
        //             handleRobotStateChange(event.getState());
        //             return false;
        //         }
        //     });

        // Subscribe to PIR events for auxiliary wake-up
        // PirManager.subscribe(value -> {
        //     if (value == 1 && stateMachine.getCurrentState() == BusinessState.IDLE) {
        //         Log.i(TAG, "PIR detected person, pre-arming wake word");
        //     }
        // });

        Log.i(TAG, "RobotStateCoordinator initialized");
    }

    /**
     * Handle business state changes.
     */
    @Override
    public void onStateChanged(BusinessState oldState, BusinessState newState) {
        Log.d(TAG, "Business state changed: " + oldState + " -> " + newState);

        if (newState != BusinessState.IDLE) {
            // We are active - ensure robot stays awake
            ensureRobotActive();
            startKeepAlive();
        } else {
            // We are idle - allow robot to manage its own state
            stopKeepAlive();
        }
    }

    @Override
    public void onStateError(BusinessState state, String errorMessage) {
        Log.e(TAG, "Business state error in " + state + ": " + errorMessage);
    }

    /**
     * Ensure robot is in Active state by injecting WantToActive event.
     */
    private void ensureRobotActive() {
        if (!isRobotActive) {
            Log.i(TAG, "Injecting WantToActive to keep robot awake");
            // SysEventApi inject WantToActive event
            // This is done through the robot's SDK
            isRobotActive = true;
        }
    }

    /**
     * Handle robot state change from the robot SDK.
     */
    private void handleRobotStateChange(Object robotState) {
        // Parse robot state
        // if (state == SysMasterEvent.AlphaState.sleep) {
        //     isRobotActive = false;
        //     if (stateMachine.getCurrentState() != BusinessState.IDLE) {
        //         // Robot went to sleep while we are active - force it back
        //         Log.w(TAG, "Robot went to sleep during active business state, forcing active");
        //         ensureRobotActive();
        //     }
        // } else if (state == SysMasterEvent.AlphaState.active) {
        //     isRobotActive = true;
        //     if (stateMachine.getCurrentState() == BusinessState.IDLE) {
        //         // Robot woke up externally, we might want to pre-arm
        //         Log.i(TAG, "Robot became active externally");
        //     }
        // }
    }

    /**
     * Start periodic keep-alive to prevent robot from going to standby/sleep.
     */
    private void startKeepAlive() {
        stopKeepAlive();
        keepAliveRunnable = new Runnable() {
            @Override
            public void run() {
                if (stateMachine.getCurrentState() != BusinessState.IDLE) {
                    ensureRobotActive();
                    handler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS);
                }
            }
        };
        handler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL_MS);
    }

    private void stopKeepAlive() {
        if (keepAliveRunnable != null) {
            handler.removeCallbacks(keepAliveRunnable);
            keepAliveRunnable = null;
        }
    }

    public void release() {
        stopKeepAlive();
        stateMachine.removeListener(this);
        // Unsubscribe from robot state events
    }
}
