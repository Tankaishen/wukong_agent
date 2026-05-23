package com.wukong.agent.manager;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.wukong.agent.R;

//import java.io.ByteArrayInputStream;
//import java.io.DataInput;
//import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PromptPlayer plays short prompt sounds (beep_hi, beep_lo) using SoundPool.
 *
 * SoundPool is chosen over MediaPlayer because:
 * - Low latency: sounds are decoded into PCM at load time, playback is near-instant
 * - Lightweight: no per-play MediaPlayer lifecycle management
 * - Concurrent with TTSEngine's AudioTrack without interference
 *
 * Completion callback is implemented by parsing the WAV header at load time to
 * calculate the exact duration, then using Handler.postDelayed(). This avoids
 * hardcoding durations — any WAV file can be swapped in without code changes.
 *
 * Usage:
 *   PromptPlayer player = new PromptPlayer(context);
 *   player.play(PromptPlayer.Prompt.WAKEUP, null);           // fire-and-forget
 *   player.play(PromptPlayer.Prompt.RECORD_END, onDone);     // with callback
 *   player.release();                                         // cleanup
 *
 * Prompt types:
 * - WAKEUP (beep_hi): played when wake word is detected, signals "I'm listening"
 * - RECORD_END (beep_lo): played when recording ends (VAD speech end), signals "processing"
 */
public class PromptPlayer {

    private static final String TAG = "PromptPlayer";

    /** Prompt sound types, mapped to res/raw/ resources */
    public enum Prompt {
        /** Wake-up beep (beep_hi.wav) — played on wake word detection */
        WAKEUP(R.raw.beep_hi),
        /** Record-end beep (beep_lo.wav) — played when recording finishes */
        RECORD_END(R.raw.beep_lo);

        final int resId;
        Prompt(int resId) {
            this.resId = resId;
        }
    }

    /** Callback interface for playback completion */
    public interface OnCompletionListener {
        /** Called when the prompt sound has finished playing */
        void onCompletion();
    }

    private SoundPool soundPool;
    private final int[] soundIds = new int[Prompt.values().length];
    /** Duration in milliseconds for each prompt, parsed from WAV header at load time */
    private final long[] durationsMs = new long[Prompt.values().length];
    private boolean loaded = false;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean released = new AtomicBoolean(false);

    public PromptPlayer(Context context) {
        this.context = context.getApplicationContext();
        initSoundPool();
    }

    private void initSoundPool() {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        // maxStreams=2: at most one WAKEUP + one RECORD_END playing simultaneously
        soundPool = new SoundPool.Builder()
                .setAudioAttributes(attrs)
                .setMaxStreams(2)
                .build();

        soundPool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            if (status == 0) {
                Log.d(TAG, "Sound loaded: sampleId=" + sampleId);
            } else {
                Log.e(TAG, "Sound load failed: sampleId=" + sampleId + " status=" + status);
            }
        });

        // Load all prompt sounds and parse their durations from WAV headers
        Prompt[] prompts = Prompt.values();
        for (int i = 0; i < prompts.length; i++) {
            soundIds[i] = soundPool.load(context, prompts[i].resId, 1);
            durationsMs[i] = parseWavDurationMs(prompts[i].resId);
            Log.i(TAG, "Prompt " + prompts[i].name() + " duration: " + durationsMs[i] + "ms");
        }
        loaded = true;
    }

    /**
     * Play a prompt sound.
     *
     * @param prompt    The prompt type to play
     * @param callback  Optional callback when playback completes.
     *                  The callback timing is based on the WAV file's actual duration
     *                  (parsed from header), so it adapts automatically to any audio file.
     */
    public void play(Prompt prompt, OnCompletionListener callback) {
        if (released.get() || !loaded || soundPool == null) {
            Log.w(TAG, "PromptPlayer not ready, skipping: " + prompt);
            if (callback != null) callback.onCompletion();
            return;
        }

        int index = prompt.ordinal();
        int soundId = soundIds[index];

        int streamId = soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f);

        if (streamId == 0) {
            Log.e(TAG, "Failed to play prompt: " + prompt);
            if (callback != null) callback.onCompletion();
            return;
        }

        Log.i(TAG, "Playing prompt: " + prompt + " streamId=" + streamId);

        if (callback != null) {
            long duration = durationsMs[index];
            handler.postDelayed(() -> {
                if (!released.get()) {
                    callback.onCompletion();
                }
            }, duration);
        }
    }

    /**
     * Parse the duration of a WAV file from its header.
     *
     * WAV format: RIFF header (12 bytes) + chunks (fmt, data, LIST, etc.)
     * The data chunk contains: "data" (4B) + chunkSize (4B) + PCM data
     * Duration = dataSize / byteRate, where byteRate is in the fmt chunk.
     *
     * The fmt chunk is always the first sub-chunk (offset 12), and contains:
     *   byteRate at offset 28 (4 bytes, little-endian)
     *
     * We scan for the "data" chunk rather than assuming a fixed offset,
     * because WAV files may contain extra chunks (e.g., LIST, INFO) between
     * fmt and data — as seen in beep_hi.wav which has a LIST chunk.
     */
    private long parseWavDurationMs(int resId) {
        try (AssetFileDescriptor afd = context.getResources().openRawResourceFd(resId);
             InputStream is = afd.createInputStream()) {

            // Read the entire file into a byte array for easy random access
            int length = (int) afd.getLength();
            byte[] buffer = new byte[length];
            int read = 0;
            while (read < length) {
                int n = is.read(buffer, read, length - read);
                if (n <= 0) break;
                read += n;
            }

            if (read < 44) {
                Log.w(TAG, "WAV too short to parse header, resId=" + resId);
                return 500; // fallback
            }

            // Validate RIFF header
            if (buffer[0] != 'R' || buffer[1] != 'I' || buffer[2] != 'F' || buffer[3] != 'F') {
                Log.w(TAG, "Not a RIFF file, resId=" + resId);
                return 500;
            }

            // Parse byteRate from fmt chunk (always at offset 28 in standard WAV)
            // fmt chunk starts at offset 12: "fmt " (4B) + chunkSize (4B) + audioFormat (2B) +
            //   numChannels (2B) + sampleRate (4B) + byteRate (4B)
            long byteRate = readUint32LE(buffer, 28);

            if (byteRate == 0) {
                Log.w(TAG, "byteRate is 0, resId=" + resId);
                return 500;
            }

            // Scan for "data" chunk — it may not be at a fixed offset
            // because WAV files can have extra chunks (LIST, INFO, etc.)
            long dataSize = 0;
            int offset = 12; // start after RIFF header (12 bytes)
            while (offset + 8 <= read) {
                // Read chunk ID (4 bytes) and chunk size (4 bytes)
                String chunkId = new String(buffer, offset, 4);
                long chunkSize = readUint32LE(buffer, offset + 4);

                if ("data".equals(chunkId)) {
                    dataSize = chunkSize;
                    break;
                }

                // Move to next chunk: 8 bytes header + chunk data
                // chunkSize is the data size (not including the 8-byte header)
                offset += 8 + (int) chunkSize;

                // WAV chunks are word-aligned (2-byte boundary)
                if ((chunkSize & 1) != 0) {
                    offset += 1;
                }
            }

            if (dataSize == 0) {
                Log.w(TAG, "No data chunk found in WAV, resId=" + resId);
                return 500;
            }

            // Duration = dataSize / byteRate (in seconds), convert to ms
            long durationMs = (dataSize * 1000) / byteRate;
            return durationMs;

        } catch (IOException e) {
            Log.e(TAG, "Failed to parse WAV duration, resId=" + resId, e);
            return 500; // fallback
        }
    }

    /**
     * Read a 32-bit unsigned little-endian integer from a byte array.
     */
    private static long readUint32LE(byte[] buf, int offset) {
        return ((buf[offset] & 0xFFL))
             | ((buf[offset + 1] & 0xFFL) << 8)
             | ((buf[offset + 2] & 0xFFL) << 16)
             | ((buf[offset + 3] & 0xFFL) << 24);
    }

    /**
     * Release all SoundPool resources.
     * Must be called when the PromptPlayer is no longer needed.
     */
    public void release() {
        released.set(true);
        handler.removeCallbacksAndMessages(null);
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        loaded = false;
        Log.i(TAG, "PromptPlayer released");
    }
}
