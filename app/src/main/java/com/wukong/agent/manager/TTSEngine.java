package com.wukong.agent.manager;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.wukong.agent.util.AudioUtils;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TTSEngine uses AudioTrack to stream-play TTS audio received from WebSocket.
 * Supports interrupt (stop immediately during playback).
 */
public class TTSEngine {

    private static final String TAG = "TTSEngine";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final long INIT_TIMEOUT_MS = 2000;

    public interface TTSListener {
        void onPlaybackStart();
        void onPlaybackComplete();
        void onPlaybackError(String errorMessage);
    }

    private AudioTrack audioTrack;
    private TTSListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isInterrupted = new AtomicBoolean(false);
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private CountDownLatch initLatch;
    private int volume = 80; // 0-100
    public TTSEngine() {}

    public void setListener(TTSListener listener) {
        this.listener = listener;
    }

    public void setVolume(int volume) {
        this.volume = Math.max(0, Math.min(100, volume));
        if (audioTrack != null) {
            float gain = this.volume / 100f;
            audioTrack.setVolume(gain);
        }
    }

    public boolean isPlaying() {
        return isPlaying.get();
    }

    /**
     * Initialize AudioTrack for streaming playback.
     */
    private void initAudioTrack() {
        int bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        // Use a larger buffer for smoother streaming
        bufferSize = Math.max(bufferSize, 8192);

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        float gain = volume / 100f;
        audioTrack.setVolume(gain);
    }

    /**
     * Start playing TTS audio.
     * Call feedAudioData() to stream more data, then call finishFeed() when done.
     */
    public void startPlayback() {
        if (isPlaying.get()) {
            Log.w(TAG, "Already playing");
            return;
        }

        Log.d(TAG, "Starting Playback");

        isInterrupted.set(false);
        isPlaying.set(true);
        isInitialized.set(false);
        initLatch = new CountDownLatch(1);

        executor.execute(() -> {
            try {
                initAudioTrack();
                audioTrack.play();
                isInitialized.set(true);
                initLatch.countDown();
                handler.post(() -> {
                    if (listener != null) listener.onPlaybackStart();
                });
                Log.i(TAG, "TTS playback started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start playback", e);
                isPlaying.set(false);
                initLatch.countDown(); // Release even on failure to avoid deadlock
                handler.post(() -> {
                    if (listener != null) listener.onPlaybackError("Start failed: " + e.getMessage());
                });
            }
        });
    }

    /**
     * Feed PCM audio data for streaming playback.
     * Blocks until AudioTrack initialization completes (with timeout).
     * @param pcmBase64 Base64 encoded PCM data
     */
    public void feedAudioData(String pcmBase64) {
        if (!isPlaying.get() || isInterrupted.get()) return;

        // Wait for AudioTrack initialization to complete before writing data.
        // This prevents audio data loss when feedAudioData() is called immediately
        // after startPlayback() — before initAudioTrack() finishes on the executor thread.
        try {
            if (initLatch != null) {
                initLatch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                Log.d(TAG,"Waiting TTS playback started");
            }
        } catch (InterruptedException e) {
            Log.w(TAG, "feedAudioData interrupted while waiting for init");
            return;
        }

        // Initialization failed or not yet complete
        if (!isInitialized.get() || audioTrack == null) return;

        byte[] pcmData = AudioUtils.base64ToPcm(pcmBase64);
        executor.execute(() -> {
            if (!isInterrupted.get() && audioTrack != null) {
                int written = audioTrack.write(pcmData, 0, pcmData.length);
            }
        });
    }

    /**
     * Signal that all audio data has been sent.
     * Will wait for playback to complete then notify.
     */
    public void finishFeed() {
        executor.execute(() -> {
            Log.d(TAG, "finishFeed call");
            if (audioTrack != null && !isInterrupted.get()) {
                try {
                    // Wait for AudioTrack to finish playing all written data
                    while (audioTrack.getPlaybackHeadPosition() < audioTrack.getBufferSizeInFrames()) {
                        Log.d(TAG, "Func:finishFeed, head:" + audioTrack.getPlaybackHeadPosition());
                        Thread.sleep(100);
                        break;
                    }

                    // Short delay to ensure final samples are played
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    // Interrupted, that's fine
                }
                Log.d(TAG, "Func:finishFeed, isInterrupted is: " + isInterrupted.get());
                if (!isInterrupted.get()) {
                    completePlayback();
                }
            }
        });
    }

    /**
     * Stop playback immediately (for interruption).
     */
    public void stopPlayback() {
        isInterrupted.set(true);
        isPlaying.set(false);
        isInitialized.set(false);

        executor.execute(() -> {
            if (audioTrack != null) {
                try {
                    audioTrack.stop();
                    audioTrack.flush();
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping AudioTrack", e);
                }
                try {
                    audioTrack.release();
                } catch (Exception e) {
                    // Ignore
                }
                audioTrack = null;
            }
        });

        Log.i(TAG, "TTS playback stopped (interrupted)");
    }

    private void completePlayback() {
        isPlaying.set(false);
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) {
                // Ignore
            }
            audioTrack = null;
        }

        handler.post(() -> {
            if (listener != null) listener.onPlaybackComplete();
        });
        Log.i(TAG, "TTS playback completed");
    }

    public void release() {
        stopPlayback();
        executor.shutdownNow();
    }
}
