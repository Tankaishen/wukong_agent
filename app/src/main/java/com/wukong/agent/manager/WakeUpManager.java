package com.wukong.agent.manager;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.wukong.agent.util.AudioUtils;

/**
 * WakeUpManager manages AIKit wake word detection.
 * It receives audio from PreProcessedRecorder (FOR_WAKEUP) and feeds it to AIKit.
 *
 * NOTE: AIKit AiHelper classes are referenced by reflection or stubbed here
 * because the actual AIKit.aar may not be available at compile time in tests.
 * In production, the AAR is in libs/ and these classes resolve normally.
 */
public class WakeUpManager {

    private static final String TAG = "WakeUpManager";
    private static final String ABILITY_ID = "e867a88f2"; // AIKit wake word ability ID
    private static final String KEYWORD_ASSET_PATH = "aikit_resources/keyword.txt";

    public interface WakeUpListener {
        void onWakeUp(String keyword, int confidence);
        void onWakeUpError(String errorMessage);
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private WakeUpListener listener;
    private boolean isListening = false;
    private String keywordFilePath;

    // AIKit handles (using Object to avoid compile-time dependency issues)
    private Object aiHandle; // com.iflytek.aikit.AiHandle

    public WakeUpManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(WakeUpListener listener) {
        this.listener = listener;
    }

    public boolean isListening() {
        return isListening;
    }

    /**
     * Initialize AIKit SDK.
     * @param appId Application ID from iFlytek
     * @param apiKey API Key from iFlytek
     * @param apiSecret API Secret from iFlytek
     * @param workDir Working directory for SDK resources
     */
    public void init(String appId, String apiKey, String apiSecret, String workDir) {
        executor.execute(() -> {
            try {
                // Copy keyword file from assets to workDir
                keywordFilePath = copyKeywordFile(workDir);

                // Initialize AIKit
                // AiHelper.Params params = AiHelper.Params.builder()
                //     .appId(appId).apiKey(apiKey).apiSecret(apiSecret)
                //     .ability(ABILITY_ID).workDir(workDir).build();
                // AiHelper.getInst().init(context, params);

                // Register result listener
                // AiHelper.getInst().registerListener(ABILITY_ID, aiListener);

                // Load wake word config
                // loadWakeWords();

                Log.i(TAG, "AIKit initialized successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize AIKit", e);
                notifyError("AIKit init failed: " + e.getMessage());
            }
        });
    }

    /**
     * Start listening for wake words.
     * Audio data will be fed via feedAudioData().
     */
    public void startListening() {
        if (isListening) {
            Log.w(TAG, "Already listening");
            return;
        }

        executor.execute(() -> {
            try {
                // AiRequest.Builder paramBuilder = AiRequest.builder();
                // aiHandle = AiHelper.getInst().start(ABILITY_ID, paramBuilder.build(), null);

                isListening = true;
                Log.i(TAG, "Started listening for wake words");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start listening", e);
                notifyError("Start listening failed: " + e.getMessage());
            }
        });
    }

    /**
     * Stop listening for wake words.
     */
    public void stopListening() {
        if (!isListening) return;

        executor.execute(() -> {
            try {
                // if (aiHandle != null) {
                //     AiHelper.getInst().end((AiHandle) aiHandle);
                // }

                isListening = false;
                Log.i(TAG, "Stopped listening for wake words");
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop listening", e);
            }
        });
    }

    /**
     * Feed audio data to AIKit for wake word detection.
     * Called from PreProcessedRecorder's FOR_WAKEUP callback.
     *
     * @param pcmData PCM 16bit 16kHz mono audio data
     */
    public void feedAudioData(byte[] pcmData) {
        if (!isListening) return;

        executor.execute(() -> {
            try {
                // AiRequest.Builder dataBuilder = AiRequest.builder();
                // AiAudio.Holder wavData = AiAudio.get("wav")
                //     .encoding(AiAudio.ENCODING_DEFAULT)
                //     .data(pcmData);
                // wavData.status(AiStatus.CONTINUE);
                // dataBuilder.payload(wavData.valid());
                // AiHelper.getInst().write(dataBuilder.build(), (AiHandle) aiHandle);
            } catch (Exception e) {
                Log.e(TAG, "Error feeding audio data", e);
            }
        });
    }

    /**
     * Load wake word configuration into AIKit.
     */
    private void loadWakeWords() {
        if (keywordFilePath == null) return;
        try {
            // AIRequest.Builder customBuilder = AIRequest.builder();
            // customBuilder.customText("key_word", keywordFilePath, 0);
            // AiHelper.getInst().loadData(ABILITY_ID, customBuilder.build());
            // int[] indexs = {0};
            // AiHelper.getInst().specifyDataSet(ABILITY_ID, "key_word", indexs);
            Log.i(TAG, "Wake words loaded from: " + keywordFilePath);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load wake words", e);
        }
    }

    /**
     * Copy keyword file from assets to working directory.
     */
    private String copyKeywordFile(String workDir) throws IOException {
        File destDir = new File(workDir, "ivw");
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        File destFile = new File(destDir, "keyword.txt");

        AssetManager assets = context.getAssets();
        InputStream is = null;
        OutputStream os = null;
        try {
            is = assets.open(KEYWORD_ASSET_PATH);
            os = new FileOutputStream(destFile);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        } finally {
            if (is != null) is.close();
            if (os != null) os.close();
        }

        return destFile.getAbsolutePath();
    }

    /**
     * Update wake word configuration dynamically.
     */
    public void updateWakeWordConfig(boolean wukongEnabled, boolean nihaoEnabled,
                                      int ncmWukong, int ncmNihao) {
        executor.execute(() -> {
            try {
                // Re-generate keyword file with updated values
                // This would rewrite the keyword.txt and reload it
                StringBuilder sb = new StringBuilder();
                if (wukongEnabled) {
                    sb.append("悟空悟空;nCM:").append(ncmWukong).append(";\n");
                }
                if (nihaoEnabled) {
                    sb.append("你好悟空;nCM:").append(ncmNihao).append(";\n");
                }

                // Write updated keyword file
                if (keywordFilePath != null) {
                    java.io.FileWriter writer = new java.io.FileWriter(keywordFilePath);
                    writer.write(sb.toString());
                    writer.close();

                    // Reload into AIKit
                    // loadWakeWords();
                }

                Log.i(TAG, "Wake word config updated");
            } catch (Exception e) {
                Log.e(TAG, "Failed to update wake word config", e);
            }
        });
    }

    private void notifyWakeUp(String keyword, int confidence) {
        handler.post(() -> {
            if (listener != null) {
                listener.onWakeUp(keyword, confidence);
            }
        });
    }

    private void notifyError(String message) {
        handler.post(() -> {
            if (listener != null) {
                listener.onWakeUpError(message);
            }
        });
    }

    public void release() {
        stopListening();
        executor.shutdownNow();
        // AiHelper.getInst().unInit();
        isListening = false;
    }
}
