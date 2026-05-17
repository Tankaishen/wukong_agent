package com.wukong.agent.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RobotActionManager controls robot actions via opensdk.
 * Currently provides interface-level abstraction; actual SDK calls are commented
 * and will be enabled when testing on real hardware.
 */
public class RobotActionManager {

    private static final String TAG = "RobotActionManager";

    public interface ActionListener {
        void onActionStarted(String actionName);
        void onActionCompleted(String actionName);
        void onActionError(String actionName, String error);
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ActionListener listener;
    private String currentAction;

    public RobotActionManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(ActionListener listener) {
        this.listener = listener;
    }

    /**
     * Play a named action on the robot.
     * @param actionName Name of the action (e.g., "head_nod", "wave_hand")
     */
    public void playAction(String actionName) {
        if (currentAction != null) {
            Log.w(TAG, "Action already in progress: " + currentAction);
            stopAction();
        }

        currentAction = actionName;
        notifyActionStarted(actionName);

        executor.execute(() -> {
            try {
                // ActionApi.get().playAction(actionName, ResourcePolicy.Exclusive,
                //     new ResponseListener<Void>() {
                //         @Override
                //         public void onSuccess(Void result) {
                //             handler.post(() -> {
                //                 currentAction = null;
                //                 notifyActionCompleted(actionName);
                //             });
                //         }
                //         @Override
                //         public void onFail(CallException e) {
                //             handler.post(() -> {
                //                 currentAction = null;
                //                 notifyActionError(actionName, e.getMessage());
                //             });
                //         }
                //     });
                Log.i(TAG, "Playing action: " + actionName);
            } catch (Exception e) {
                Log.e(TAG, "Failed to play action: " + actionName, e);
                currentAction = null;
                notifyActionError(actionName, e.getMessage());
            }
        });
    }

    /**
     * Play an expression on the robot's eye screens.
     * @param expressionName Name of the expression
     */
    public void playExpression(String expressionName) {
        executor.execute(() -> {
            try {
                // ExpressApi.get().doExpress(expressionName, 1, ResourcePolicy.GiveUp);
                Log.i(TAG, "Playing expression: " + expressionName);
            } catch (Exception e) {
                Log.e(TAG, "Failed to play expression: " + expressionName, e);
            }
        });
    }

    /**
     * Stop the current action.
     */
    public void stopAction() {
        if (currentAction == null) return;
        String action = currentAction;
        currentAction = null;

        executor.execute(() -> {
            try {
                // ActionApi.get().stopAction();
                Log.i(TAG, "Stopped action: " + action);
                notifyActionCompleted(action);
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop action", e);
            }
        });
    }

    /**
     * Stop the current expression.
     */
    public void stopExpression() {
        executor.execute(() -> {
            try {
                // ExpressApi.get().stopExpress();
                Log.i(TAG, "Stopped expression");
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop expression", e);
            }
        });
    }

    /**
     * Control a servo motor.
     */
    public void controlServo(int motorId, int angle, int durationMs) {
        executor.execute(() -> {
            try {
                // MotorApi.get().moveToAbsoluteAngle(motorId, angle, durationMs,
                //     ResourcePolicy.Exclusive, null);
                Log.i(TAG, "Servo control: id=" + motorId + " angle=" + angle);
            } catch (Exception e) {
                Log.e(TAG, "Failed to control servo", e);
            }
        });
    }

    /**
     * Control chest/mouth lights.
     */
    public void controlLight(List<Integer> ids, int color, int durationMs) {
        executor.execute(() -> {
            try {
                // LightApi.getInstance().normalEffect(ids, color, durationMs, false);
                Log.i(TAG, "Light control: ids=" + ids + " color=" + color);
            } catch (Exception e) {
                Log.e(TAG, "Failed to control light", e);
            }
        });
    }

    public boolean isActionPlaying() {
        return currentAction != null;
    }

    private void notifyActionStarted(String name) {
        handler.post(() -> {
            if (listener != null) listener.onActionStarted(name);
        });
    }

    private void notifyActionCompleted(String name) {
        handler.post(() -> {
            if (listener != null) listener.onActionCompleted(name);
        });
    }

    private void notifyActionError(String name, String error) {
        handler.post(() -> {
            if (listener != null) listener.onActionError(name, error);
        });
    }

    public void release() {
        stopAction();
        executor.shutdownNow();
    }
}
