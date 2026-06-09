package com.wukong.agent.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.RemoteViews;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.wukong.agent.R;
import com.wukong.agent.coordinator.RobotStateCoordinator;
import com.wukong.agent.data.db.WukongDatabase;
import com.wukong.agent.data.repository.ChatHistoryRepository;
import com.wukong.agent.data.repository.WakeUpLogRepository;
import com.wukong.agent.data.repository.ActionRepository;
import com.wukong.agent.factories.WakeEngineFactory;
import com.wukong.agent.interfaces.IWakeUpEngine;
import com.wukong.agent.manager.*;
import com.wukong.agent.model.RobotConfig;
import com.wukong.agent.model.WebSocketMessage;
import com.wukong.agent.settings.SettingsActivity;
import com.wukong.agent.statemachine.BusinessState;
import com.wukong.agent.statemachine.BusinessStateMachine;
import com.wukong.agent.statemachine.StateChangeListener;
import com.wukong.agent.util.AudioUtils;
import com.wukong.agent.watchdog.ServiceWatchdog;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WukongService extends Service implements
        StateChangeListener,
        ConfigManager.ConfigListener,
        IWakeUpEngine.WakeUpListener,
        IWakeUpEngine.InitCallback,
        AudioRecorderManager.AudioListener,
        AudioRecorderManager.InitCallback,
        WebSocketManager.WebSocketEventListener,
        TTSEngine.TTSListener {

    /**
     * Static reference to the current service instance for state queries.
     * Set in onCreate(), cleared in onDestroy(). WeakReference alternative is overkill
     * since service lifecycle is well-defined (system guarantees onCreate/Destroy pairing).
     */
    private static volatile WukongService instance = null;
    private static final String TAG = "WukongService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "wukong_service_channel";
    private static final String PREF_KEY_USER_STOPPED = "pref_user_stopped_service";

    /**
     * Static volatile flag indicating whether the service is currently running.
     * - Set true in onCreate(), false in onDestroy()
     * - volatile ensures visibility across threads (watchdog JobScheduler may run
     *   in a different thread within the same process)
     * - If the process is killed by the system, this resets to false (default),
     *   which is exactly what the watchdog needs to detect.
     */
    private static volatile boolean isRunning = false; // 核心双标志机制的第一标志

    /**
     * Check if the service is currently running.
     * Used by ServiceWatchdog to determine if a restart is needed.
     */
    public static boolean isRunning() {
        return isRunning;
    }

    /**
     * Get the current business state of the service.
     * Returns null if the service is not running.
     * Used by SettingsFragment to display real-time state.
     */
    public static BusinessState getCurrentState() {
        if (instance == null || instance.stateMachine == null) return null;
        return instance.stateMachine.getCurrentState();
    }

    // Core components, managers
    private BusinessStateMachine stateMachine;
    private RobotStateCoordinator coordinator;
    private ConfigManager configManager;
    private IWakeUpEngine wakeUpEngine;
    private AudioRecorderManager audioRecorderManager;
    private WebSocketManager webSocketManager;
    private TTSEngine ttsEngine;
    private PromptPlayer promptPlayer;
    private RobotActionManager robotActionManager;

    // Data
    private WukongDatabase database;
    private ChatHistoryRepository chatHistoryRepository;
    private WakeUpLogRepository wakeUpLogRepository;
    private ActionRepository actionRepository;

    // State
//    private final Handler handler = new Handler(Looper.getMainLooper());
    private NotificationManager notificationManager;
    private RemoteViews notificationLayout;

    // 初始化条件变量。
    private CountDownLatch initLatch;
    private static final long INIT_TIMEOUT_MS = 2000;

    @Override
    public void onCreate() {
        super.onCreate();

        isRunning = true;
        instance = this;
        clearUserStoppedFlag();

        initNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, buildNotification(BusinessState.IDLE),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE |
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(BusinessState.IDLE));
        }


        initComponents();
        try {
            if (initLatch != null) {
                initLatch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Log.w(TAG, "feedAudioData interrupted while waiting for init");
            return;
        }
        wakeUpEngine.startListening();
        startStateMachine();
        promptPlayer.play(PromptPlayer.Prompt.BOOTINGSUCCESS, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "WukongService onStartCommand");
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // No binding
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "WukongService onDestroy");
        isRunning = false;
        instance = null;
        cleanup();
        // Schedule restart (only effective if not user-initiated stop)
        Intent intent = new Intent("com.wukong.agent.RESTART_SERVICE");
        intent.setPackage(getPackageName()); // 原始代码是去掉这一行
        sendBroadcast(intent);
        super.onDestroy();
    }

    // ==================== Initialization ====================

    private void initComponents() {

        initLatch = new CountDownLatch(2);
        // Database
        database = WukongDatabase.getInstance(this);
        chatHistoryRepository = new ChatHistoryRepository(database.chatHistoryDao());
        wakeUpLogRepository = new WakeUpLogRepository(database.wakeUpLogDao());
        actionRepository = new ActionRepository(database.actionDao());

        // State machine (MUST be created before observeForever, because LiveData may
        // dispatch cached value synchronously on re-registration, triggering onConfigChanged)
        stateMachine = new BusinessStateMachine();
        stateMachine.addListener(this);

        // Config — observeForever may fire onConfigChanged immediately if LiveData has a cached value,
        // so all components referenced in onConfigChanged must already be initialized.
        configManager = ConfigManager.getInstance(this);
        configManager.setConfigListener(this);
//        configManager.getConfigLiveData().observeForever(this::onConfigChanged);
        stateMachine.applyConfigToStateMachine(configManager.getConfig());

        // Wake engine (created via factory based on config)
        RobotConfig config = configManager.getConfig();
        wakeUpEngine = WakeEngineFactory.create(config.getWakeEngineType());
        wakeUpEngine.setListener(this);
        wakeUpEngine.setInitCallback(this);
        wakeUpEngine.init(this, config.getWakeEngineCredentials());

        audioRecorderManager = new AudioRecorderManager(this, config.getRecorderType());
        audioRecorderManager.setAsrListener(this);
        audioRecorderManager.setInitCallback(this);
        audioRecorderManager.setWakeUpEngine(wakeUpEngine);
        audioRecorderManager.setVadParameters(
            configManager.getVadEnergyThreshold(),
            configManager.getVadSilenceDurationMs());

        webSocketManager = new WebSocketManager();
        webSocketManager.setEventListener(this);
        webSocketManager.setServerUrl(configManager.getWsServerUrl());

        ttsEngine = new TTSEngine();
        ttsEngine.setListener(this);

        promptPlayer = new PromptPlayer(this);

        robotActionManager = new RobotActionManager(this);

        // Coordinator
        coordinator = new RobotStateCoordinator(this, stateMachine);
        coordinator.init();

        // Initialize PreProcessedRecorder
        audioRecorderManager.init();

        // Connect WebSocket
        webSocketManager.connect();
    }

    private void startStateMachine() {
        Log.d(TAG, "Start State Machine!");
        stateMachine.transitionTo(BusinessState.IDLE);
        // IDLE state's onStateChanged callback will start wakeup listening + wake engine
    }

    // ==================== State Change Handling ====================

    @Override
    public void onStateChanged(BusinessState oldState, BusinessState newState) {
        Log.i(TAG, "State: " + oldState + " -> " + newState);
        updateNotification(newState);

        switch (newState) {
            case IDLE:
                audioRecorderManager.stopAsrRecording();
                audioRecorderManager.startWakeupListening();
//                wakeUpEngine.startListening();
                break;

            case WAKEUP:
                webSocketManager.connect();
                // Wake word detected — stop wakeup listening, prepare for ASR
                audioRecorderManager.stopWakeupListening();
//                wakeUpEngine.stopListening();
                // Play wake-up prompt (beep_hi), then transition to RECORDING after it finishes
                promptPlayer.play(PromptPlayer.Prompt.WAKEUP, () -> {
                    if (stateMachine.getCurrentState() == BusinessState.WAKEUP) {
                        stateMachine.transitionTo(BusinessState.RECORDING, "wakeup_prompt_done");
                    }
                });
                break;

            case RECORDING:
                // Start ASR recording: register FOR_ASR listener
                // Clear cancelled sessions from previous cycle to prevent memory leak
                webSocketManager.clearCancelledSessions();
                audioRecorderManager.startAsrRecording();
                break;

            case PROCESSING:
                // Stop ASR recording, play record-end prompt, send final marker to WebSocket
                audioRecorderManager.stopAsrRecording();
                promptPlayer.play(PromptPlayer.Prompt.RECORD_END, null);
                // Enable wakeup audio forwarding + start wake engine for interrupt detection
                audioRecorderManager.startWakeupListening();
//                wakeUpEngine.startListening();
                String sessionId = audioRecorderManager.getCurrentSessionId();
                if (sessionId != null) {
                    webSocketManager.sendChatMessage(sessionId, "", true);
                }
                break;

            case PLAYING:
                // TTS playback started by onMessage callback
                // Action started by onMessage callback
                break;
        }
    }

    @Override
    public void onStateError(BusinessState state, String errorMessage) {
        Log.e(TAG, "State error in " + state + ": " + errorMessage);
        // On error, return to IDLE
        if (stateMachine.getCurrentState() != BusinessState.IDLE) {
            stateMachine.forceTransitionTo(BusinessState.IDLE, "error: " + errorMessage);
        }
    }

    // ==================== WakeUp Callbacks ====================

    @Override
    public void onWakeUp(String keyword, int confidence) {
        Log.i(TAG, "Wake word detected: " + keyword + " confidence: " + confidence);

        BusinessState current = stateMachine.getCurrentState();

        if (current == BusinessState.IDLE) {
            // Normal wake up from idle
            stateMachine.transitionTo(BusinessState.WAKEUP, "wake: " + keyword);
        } else if (current == BusinessState.PROCESSING || current == BusinessState.PLAYING) {
            // Interrupt: stop current output and start a new conversation cycle
            handleInterrupt("interrupt: " + keyword);
        } else {
            // WAKEUP or RECORDING: already listening, ignore
            Log.d(TAG, "Wake word during " + current + ", ignoring");
        }
    }

    /**
     * Unified interrupt handler for PROCESSING and PLAYING states.
     * Stops all output (TTS, actions), cancels the current WS session,
     * then transitions to WAKEUP which plays beep_hi and starts a new recording.
     */
    private void handleInterrupt(String reason) {
        Log.i(TAG, "handleInterrupt: " + reason);

        // 1. Cancel the current WebSocket session (prevent stale TTS from being played)
        String sessionId = audioRecorderManager.getCurrentSessionId();
        if (sessionId != null) {
            webSocketManager.cancelSession(sessionId);
        }

        // 2. Stop TTS playback immediately
        ttsEngine.stopPlayback();

        // 3. Stop robot actions
        robotActionManager.stopAction();

        // 4. Stop ASR recording (might still be active if interrupting from PROCESSING)
        audioRecorderManager.stopAsrRecording();

        // 5. Stop wakeup listening and wake engine
        audioRecorderManager.stopWakeupListening();
//        wakeUpEngine.stopListening();

        while(TTSEngine.isPlaying());
        // 6. Transition to WAKEUP — plays beep_hi, reconnects WS, then auto-transitions to RECORDING
        stateMachine.forceTransitionTo(BusinessState.WAKEUP, reason);
    }

    @Override
    public void onWakeUpError(String errorMessage) {
        Log.e(TAG, "Wake word error: " + errorMessage);
        stateMachine.notifyError(stateMachine.getCurrentState(), errorMessage);
    }

    // ==================== Audio Recorder Callbacks ====================

    @Override
    public void onAudioData(byte[] pcmData) {
        if (stateMachine.getCurrentState() != BusinessState.RECORDING) return;

        String base64 = AudioUtils.pcmToBase64(pcmData);
        String sessionId = audioRecorderManager.getCurrentSessionId();
        webSocketManager.sendChatMessage(sessionId, base64, false);
    }

    @Override
    public void onVadSpeechStart() {
        Log.d(TAG, "VAD: speech started");
    }

    @Override
    public void onVadSpeechEnd() {
        Log.d(TAG, "VAD: speech ended");
        if (stateMachine.getCurrentState() == BusinessState.RECORDING) {
            stateMachine.transitionTo(BusinessState.PROCESSING, "vad_end");
        }
    }

    @Override
    public void onRecordingError(String errorMessage) {
        Log.e(TAG, "Recording error: " + errorMessage);
        // If recorder hardware isn't ready yet, don't force IDLE —
        // the retry logic in AudioRecorderManager will call onRecorderReady() when it succeeds.
        // Only force IDLE for non-hardware errors (e.g. actual recording failures).
        if (!audioRecorderManager.isRecorderReady()) {
            Log.w(TAG, "Recorder not ready, waiting for retry — not forcing IDLE");
            return;
        }
        stateMachine.forceTransitionTo(BusinessState.IDLE, "recording_error");
    }

    // ==================== WebSocket Callbacks ====================

    @Override
    public void onConnected() {
        Log.i(TAG, "WebSocket connected");
    }

    @Override
    public void onDisconnected(String reason) {
        Log.w(TAG, "WebSocket disconnected: " + reason);
    }

    @Override
    public void onMessage(WebSocketMessage message) {
        String msgSessionId = message.getSessionId();
        String currentSessionId = audioRecorderManager.getCurrentSessionId();
        boolean sessionMatch = msgSessionId != null && msgSessionId.equals(currentSessionId);

        if (sessionMatch && message.isTts()) {
            handleTtsMessage(message);
        } else if (message.isError()) {
            Log.e(TAG, "Server error: " + message.getError());
            stateMachine.forceTransitionTo(BusinessState.IDLE, "server_error");
        } else {
            Log.w(TAG, "Message session mismatch or unknown type: msgSession=" + msgSessionId
                    + ", currentSession=" + currentSessionId + ", isTts=" + message.isTts());
        }
    }

    @Override
    public void onError(String errorMessage) {
        Log.e(TAG, "WebSocket error: " + errorMessage);
    }

    private void handleTtsMessage(WebSocketMessage message) {
        // Discard TTS for cancelled sessions (e.g. after interrupt)
        String msgSessionId = message.getSessionId();
        if (msgSessionId != null && webSocketManager.checkAndRemoveCancelled(msgSessionId)) {
            Log.w(TAG, "Discarding TTS for cancelled session: " + msgSessionId);
            return;
        }

        if (stateMachine.getCurrentState() == BusinessState.PROCESSING) {
            stateMachine.transitionTo(BusinessState.PLAYING, "tts_start");
            ttsEngine.startPlayback();
        }
//        Log.d(TAG,"In Func:handleTtsMessage: message.getAudioBase64()=" + message.getAudioBase64());
//        Log.d(TAG,"In Func:handleTtsMessage: message.getAudioBase64().isEmpty()=" + message.getAudioBase64().isEmpty());
        if (message.getAudioBase64() != null && !message.getAudioBase64().isEmpty()) {
            ttsEngine.feedAudioData(message.getAudioBase64());
        }

        // Execute action if specified, 后续开发
//        if (message.getAction() != null && !message.getAction().isEmpty()) {
//            robotActionManager.playAction(message.getAction());
//        }

        // Save text to chat history
        if (message.getText() != null && !message.getText().isEmpty()) {
            String sessionId = audioRecorderManager.getCurrentSessionId();
            chatHistoryRepository.insertAsync(
                new com.wukong.agent.data.entity.ChatHistoryEntity(
                    sessionId != null ? sessionId : "unknown",
                    "assistant",
                    message.getText(),
                    System.currentTimeMillis()),
                null);
        }
        Log.d(TAG,"In Func:handleTtsMessage: message.getText()=" + message.getText());
        Log.d(TAG,"In Func:handleTtsMessage: message.getText().isEmpty()=" + message.getText().isEmpty());

        if (message.isFinal()) {
            Log.d(TAG,"Receive message.isFinal!");
            ttsEngine.finishFeed();
        }
    }

    // ==================== TTS Callbacks ====================

    @Override
    public void onPlaybackStart() {
        Log.i(TAG, "TTS playback started");
    }

    @Override
    public void onPlaybackComplete() {
        Log.i(TAG, "TTS playback completed");
        if (stateMachine.getCurrentState() == BusinessState.PLAYING) {
            stateMachine.transitionTo(BusinessState.IDLE, "tts_complete");
        }
    }

    @Override
    public void onPlaybackError(String errorMessage) {
        Log.e(TAG, "TTS playback error: " + errorMessage);
        stateMachine.forceTransitionTo(BusinessState.IDLE, "tts_error");
    }

    // ==================== Config Change ====================

    @Override
    public void onConfigChanged(RobotConfig config) {
        if (config == null) return;

        // Apply timeout settings
        if (stateMachine != null) {
            stateMachine.applyConfigToStateMachine(config);
        }

        // Update VAD parameters
        if (audioRecorderManager != null) {
            audioRecorderManager.setVadParameters(
                config.getVadEnergyThreshold(),
                config.getVadSilenceDurationMs());
        }

        // Update TTS volume
        if (ttsEngine != null) {
            ttsEngine.setVolume(config.getTtsVolume());
        }

        // Update WebSocket URL if changed
        if (webSocketManager != null) {
            if (!config.getWsServerUrl().equals(webSocketManager.isConnected() ? "connected" : "")) {
                webSocketManager.setServerUrl(config.getWsServerUrl());
            }
        }

        // Update wake word config
        if (wakeUpEngine != null) {
            wakeUpEngine.updateWakeWordConfig(
                config.isWakeWukongEnabled(),
                config.isWakeNihaoEnabled(),
                config.getNcmWukong(),
                config.getNcmNihao());
        }
    }

//    private void applyConfigToStateMachine(RobotConfig config) {
//        stateMachine.setRecordingTimeoutMs(config.getRecordingTimeoutMs());
//        stateMachine.setProcessingTimeoutMs(config.getProcessingTimeoutMs());
//        stateMachine.setPlayingTimeoutMs(config.getPlayingTimeoutMs());
//    }

    // ==================== Notification ====================

    private void initNotificationChannel() {
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notification_channel_desc));
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(BusinessState state) {
        Intent settingsIntent = new Intent(this, SettingsActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String contentText;
        switch (state) {
            case RECORDING:
                contentText = getString(R.string.notification_text_recording);
                break;
            case PLAYING:
                contentText = getString(R.string.notification_text_playing);
                break;
            default:
                contentText = getString(R.string.notification_text_idle);
                break;
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(contentText)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(BusinessState state) {
        // Android 13+ 没有 POST_NOTIFICATIONS 权限时，notificationManager.notify() 会被静默忽略
        // ForegroundService 的初始通知不受此限制，但后续更新需要权限
        if (!hasNotificationPermission()) {
            Log.d(TAG, "Skipping notification update: POST_NOTIFICATIONS not granted");
            return;
        }
        Notification notification = buildNotification(state);
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    /**
     * 检查是否有通知发布权限。
     * Android 13 (API 33) 之前不需要运行时权限，直接返回 true。
     */
    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    // ==================== Cleanup ====================

    private void cleanup() {
        if (coordinator != null) coordinator.release();
        if (wakeUpEngine != null) wakeUpEngine.release();
        if (audioRecorderManager != null) audioRecorderManager.release();
        if (webSocketManager != null) webSocketManager.release();
        if (ttsEngine != null) ttsEngine.release();
        if (promptPlayer != null) promptPlayer.release();
        if (robotActionManager != null) robotActionManager.release();
        if (stateMachine != null) stateMachine.cleanup();
        if (configManager != null) {
//            configManager.getConfigLiveData().removeObserver(this::onConfigChanged);
            configManager.release();
        }
    }

    // ==================== Static Control ====================

    /**
     * Check if RECORD_AUDIO permission is granted.
     * If not, launch PermissionActivity to request it from the user.
     * @return true if already granted, false if requesting (async).
     */
    private boolean ensureAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG,"AUDIORECORDER PERMISSION GRANTED");
            return true;
        }
        Log.d(TAG,"AUDIORECORDER PERMISSION NOT GRANTED");
        // Launch transparent Activity to show system permission dialog
        Intent permIntent = new Intent(this, PermissionActivity.class);
        permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(permIntent);
        return false;
    }

    public static void start(Context context) {
        if (isRunning()){
            Log.i(TAG, "WukongService is running — no action needed");
            return;
        }
        Intent intent = new Intent(context, WukongService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        // Mark as user-initiated stop so watchdog does not restart
        setUserStoppedFlag(context);
        ServiceWatchdog.cancelWatchdog(context);
        Intent intent = new Intent(context, WukongService.class);
        context.stopService(intent);
    }

    // ==================== Service Status Flags ====================

    /**
     * Set the "user stopped" flag in SharedPreferences.
     * This tells the watchdog NOT to restart the service.
     * The flag is persisted across process deaths and reboots.
     */
    private static void setUserStoppedFlag(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        prefs.edit().putBoolean(PREF_KEY_USER_STOPPED, true).apply();
        Log.i(TAG, "User stopped flag set — watchdog will not restart");
    }

    /**
     * Clear the "user stopped" flag.
     * Called in onCreate() so that if the service is started again
     * (by boot, by user, or by any other means), the watchdog
     * will resume its protection.
     */
    private void clearUserStoppedFlag() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        prefs.edit().putBoolean(PREF_KEY_USER_STOPPED, false).apply();
        Log.i(TAG, "User stopped flag cleared — watchdog protection active");
    }

    /**
     * Check whether the user has explicitly stopped the service.
     * Used by ServiceWatchdog to decide if it should restart.
     */
    public static boolean isUserStopped(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        return prefs.getBoolean(PREF_KEY_USER_STOPPED, false);
    }

    @Override
    public void onInitComplete() {
        initLatch.countDown();
    }

    @Override
    public void onRecorderReady() {
        // Called when PreProcessedRecorder hardware is successfully started
        // (may be delayed due to retry at boot time).
        // If we're in IDLE state, try to start wakeup listening now.
        Log.i(TAG, "Recorder hardware ready");
        if (stateMachine != null && stateMachine.getCurrentState() == BusinessState.IDLE) {
            audioRecorderManager.startWakeupListening();
            wakeUpEngine.startListening();
            Log.i(TAG, "Wakeup listening started after recorder became ready");
        }
    }
}
