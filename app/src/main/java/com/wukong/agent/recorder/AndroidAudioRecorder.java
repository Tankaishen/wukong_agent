package com.wukong.agent.recorder;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.wukong.agent.interfaces.IRecorder;
import com.wukong.agent.interfaces.IWakeUpEngine;
import com.wukong.agent.util.VADDetector;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IRecorder implementation using android.media.AudioRecord.
 *
 * Design:
 * - Single AudioRecord instance, 16kHz mono 16bit PCM via VOICE_RECOGNITION source
 * - One read thread loops AudioRecord.read(), gates data forwarding via AtomicBoolean flags
 * - wakeup audio → IWakeUpEngine.feedAudioData()
 * - asr audio   → AudioListener.onAudioData() + VAD detection
 * - No network dependency, no SDK init wait
 *
 * Boot-time resilience:
 * - AudioFlinger may not be ready when the service starts at boot
 * - init() returns immediately (calls onInitComplete), retries AudioRecord creation
 *   in the background every 3s indefinitely until success or release()
 * - startWakeupListening()/startAsrRecording() set flags regardless of readiness;
 *   when AudioRecord eventually initializes, the read thread picks up the flags
 * - onRecorderReady() is called only when AudioRecord actually starts capturing
 *
 * AEC note: VOICE_RECOGNITION source enables AGC automatically.
 * AcousticEchoCanceler is not used in mono mode (no REF channel for echo reference).
 * Future: switch to multi-channel config to enable AEC with REF1 playback feedback.
 */
public class AndroidAudioRecorder implements IRecorder {

    private static final String TAG = "AndroidAudioRecorder";

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    /** Read buffer size in bytes. getMinBufferSize returns a minimum; we use 2x for safety. */
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 2;

    /** Fixed retry interval: AudioFlinger boot time is unpredictable,
     *  so we retry indefinitely until success or release(). */
    private static final long RETRY_INTERVAL_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // External dependencies
    private IWakeUpEngine wakeUpEngine;
    private AudioListener asrListener;
    private VADDetector vadDetector;

    // State tracking
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean hardwareReady = new AtomicBoolean(false);
    private final AtomicBoolean wakeupListening = new AtomicBoolean(false);
    private final AtomicBoolean asrRecording = new AtomicBoolean(false);
    private final AtomicBoolean reading = new AtomicBoolean(false);
    private final AtomicBoolean initAborted = new AtomicBoolean(false);
    private String currentSessionId;

    // AudioRecord instance
    private volatile AudioRecord audioRecord;
    private Thread readThread;

    // ==================== Configuration ====================

    @Override
    public void setWakeUpEngine(IWakeUpEngine engine) {
        this.wakeUpEngine = engine;
    }

    @Override
    public void setAsrListener(AudioListener listener) {
        this.asrListener = listener;
    }

    @Override
    public void setVadParameters(int energyThreshold, int silenceDurationMs) {
        this.vadDetector = new VADDetector(energyThreshold, silenceDurationMs);
        this.vadDetector.setListener(new VADDetector.VADListener() {
            @Override
            public void onSpeechStart() {
                handler.post(() -> {
                    if (asrListener != null) asrListener.onVadSpeechStart();
                });
            }

            @Override
            public void onSpeechEnd() {
                handler.post(() -> {
                    if (asrListener != null) asrListener.onVadSpeechEnd();
                });
            }
        });
    }

    // ==================== Lifecycle ====================

    @Override
    public void init(Context context, InitCallback callback) {
        if (initialized.get()) {
            Log.w(TAG, "AudioRecord already initialized");
            return;
        }
        initAborted.set(false);

        // Notify init started immediately — don't block service startup.
        // The recorder may not be ready yet (AudioFlinger boot delay),
        // but the service can proceed. onRecorderReady() will be called
        // once AudioRecord actually starts capturing.
        handler.post(() -> { if (callback != null) callback.onInitComplete(); });

        executor.execute(() -> initWithRetry(callback, 0));
    }

    /**
     * Infinite retry for AudioRecord initialization.
     *
     * At boot time, AudioFlinger may not be ready for an unpredictable duration,
     * so we retry every {@link #RETRY_INTERVAL_MS} until success or release().
     *
     * onInitComplete() is already called before the first attempt —
     * the service proceeds without blocking. onRecorderReady() is called
     * only when AudioRecord actually starts recording.
     */
    private void initWithRetry(InitCallback callback, int attempt) {
//        if (initAborted.get()) {
//            Log.i(TAG, "Init aborted (release called), stopping retries");
//            return;
//        }

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    BUFFER_SIZE);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord not initialized (attempt " + (attempt + 1)
                        + "), state: " + audioRecord.getState()
                        + " — AudioFlinger likely not ready yet");
                audioRecord.release();
                audioRecord = null;

                if (!initAborted.get()) {
                    Log.i(TAG, "Retrying init in " + RETRY_INTERVAL_MS + "ms...");
                    handler.postDelayed(
                            () -> executor.execute(() -> initWithRetry(callback, attempt + 1)),
                            RETRY_INTERVAL_MS);
                }
                return;
            }

            // Success
            initialized.set(true);
            Log.i(TAG, "AudioRecord initialized successfully on attempt " + (attempt + 1)
                    + " (bufferSize=" + BUFFER_SIZE + ")");

            audioRecord.startRecording();
            hardwareReady.set(true);
            startReadThread();

            Log.i(TAG, "AudioRecord hardware started");

            handler.post(() -> {
                if (callback != null) {
                    callback.onRecorderReady();
                }
            });

        } catch (SecurityException e) {
            Log.e(TAG, "RECORD_AUDIO permission not granted", e);
            notifyError("RECORD_AUDIO permission not granted");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid AudioRecord parameters", e);
            notifyError("Invalid AudioRecord parameters: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Failed to init AudioRecord", e);
            notifyError("Init failed: " + e.getMessage());
        }
    }

    /**
     * Start the read thread that continuously reads audio data from AudioRecord.
     * The thread checks AtomicBoolean flags to decide where to forward data.
     */
    private void startReadThread() {
        if (reading.getAndSet(true)) {
            Log.d(TAG, "Read thread already running");
            return;
        }

        readThread = new Thread(() -> {
            Log.i(TAG, "Read thread started");
            byte[] buffer = new byte[BUFFER_SIZE];

            while (reading.get() && audioRecord != null) {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                if (bytesRead <= 0) {
//                    Log.w(TAG, "AudioRecord.read() returned " + bytesRead);
                    continue;
                }

                // Forward wakeup audio
                if (wakeupListening.get() && wakeUpEngine != null && wakeUpEngine.isListening()) {
                    byte[] wakeupChunk = new byte[bytesRead];
                    System.arraycopy(buffer, 0, wakeupChunk, 0, bytesRead);
                    wakeUpEngine.feedAudioData(wakeupChunk);
                }

                // Forward ASR audio + VAD
                if (asrRecording.get()) {
                    byte[] asrChunk = new byte[bytesRead];
                    System.arraycopy(buffer, 0, asrChunk, 0, bytesRead);

                    // VAD on single-channel data
                    if (vadDetector != null) {
                        long chunkDurationMs = (asrChunk.length / 2) * 1000L / SAMPLE_RATE;
                        vadDetector.processAudioChunk(asrChunk, chunkDurationMs);
                    }

                    if (asrListener != null) {
                        asrListener.onAudioData(asrChunk);
                    }
                }
            }

            Log.i(TAG, "Read thread exited");
        }, "AudioRecord-ReadThread");

        readThread.setPriority(Thread.MAX_PRIORITY);
        readThread.start();
    }

    // ==================== Wakeup Audio Control ====================

    @Override
    public void startWakeupListening() {
        // Set flag regardless of readiness — the read thread will pick it up
        // once AudioRecord initializes (lazy activation for boot-time resilience).
        wakeupListening.set(true);
        if (!isReady()) {
            Log.w(TAG, "Wakeup listening requested but recorder not ready yet — flag set, will activate on init");
        } else {
            Log.i(TAG, "Wakeup listening enabled");
        }
    }

    @Override
    public void stopWakeupListening() {
        wakeupListening.set(false);
        Log.i(TAG, "Wakeup listening disabled");
    }

    // ==================== ASR Audio Control ====================

    @Override
    public String startAsrRecording() {
        currentSessionId = UUID.randomUUID().toString();
        if (vadDetector != null) {
            vadDetector.reset();
        }
        asrRecording.set(true);
        if (!isReady()) {
            Log.w(TAG, "ASR recording requested but recorder not ready yet — flag set, will activate on init");
        } else {
            Log.i(TAG, "ASR recording enabled, session: " + currentSessionId);
        }
        return currentSessionId;
    }

    @Override
    public void stopAsrRecording() {
        asrRecording.set(false);
        Log.i(TAG, "ASR recording disabled");
    }

    // ==================== State Queries ====================

    @Override
    public boolean isReady() {
        return initialized.get() && hardwareReady.get();
    }

    @Override
    public boolean isWakeupListening() {
        return wakeupListening.get();
    }

    @Override
    public boolean isAsrRecording() {
        return asrRecording.get();
    }

    @Override
    public String getCurrentSessionId() {
        return currentSessionId;
    }

    // ==================== Internal ====================

    private void notifyError(String message) {
        handler.post(() -> {
            if (asrListener != null) {
                asrListener.onRecordingError(message);
            }
        });
    }

    // ==================== Release ====================

    @Override
    public void release() {
        // Abort any pending init retries
        initAborted.set(true);
        handler.removeCallbacksAndMessages(null);

        stopWakeupListening();
        stopAsrRecording();

        reading.set(false);
        if (readThread != null) {
            try {
                readThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            readThread = null;
        }

        if (audioRecord != null) {
            try {
                if (hardwareReady.get()) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing AudioRecord", e);
            }
            audioRecord = null;
        }

        initialized.set(false);
        hardwareReady.set(false);

        executor.shutdownNow();
        Log.i(TAG, "AndroidAudioRecorder released");
    }
}
