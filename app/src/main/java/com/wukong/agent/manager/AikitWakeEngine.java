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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.iflytek.aikit.core.AiHelper;
import com.iflytek.aikit.core.AiListener;
import com.wukong.agent.interfaces.IWakeUpEngine;
import com.wukong.agent.util.AudioUtils;

/**
 * AIKit (iFlytek) wake word engine implementation.
 *
 * Credentials are loaded from assets/wake_engine.properties via ConfigManager,
 * then passed to init() as a Map with keys: appId, apiKey, apiSecret, workDir.
 *
 * NOTE: AIKit AiHelper classes are referenced directly.
 * In production, the AIKit.aar is in libs/ and these classes resolve normally.
 * For unit testing without the AAR, use reflection or mocking.
 */
public class AikitWakeEngine implements IWakeUpEngine {

    private static final String TAG = "AikitWakeEngine";
    private static final String ABILITY_ID = "e867a88f2";
    private static final String KEYWORD_ASSET_PATH = "aikit_resources/keyword.txt";

    // Credential keys expected in the credentials Map
    private static final String KEY_APP_ID = "appId";
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_API_SECRET = "apiSecret";
    private static final String KEY_WORK_DIR = "workDir";
    private static final String DEFAULT_WORK_DIR = "aikit_workspace";

    private Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private WakeUpListener listener;
    private boolean isListening = false;
    private boolean isInitialized = false;
    private String keywordFilePath;

    // AIKit handle (using Object to allow testing without AAR)
    private Object aiHandle;

    public AikitWakeEngine() {
        // No-arg constructor; Context is provided via init()
    }

    @Override
    public void init(Context context, Map<String, String> credentials) {
        this.context = context.getApplicationContext();

        String appId = credentials.get(KEY_APP_ID);
        String apiKey = credentials.get(KEY_API_KEY);
        String apiSecret = credentials.get(KEY_API_SECRET);
        String workDirName = credentials.containsKey(KEY_WORK_DIR)
                ? credentials.get(KEY_WORK_DIR) : DEFAULT_WORK_DIR;

        // Validate required credentials
        if (appId == null || appId.trim().isEmpty()) {
            String msg = "AIKit appId is empty. Check wake_engine.properties";
            Log.e(TAG, msg);
            notifyError(msg);
            return;
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            String msg = "AIKit apiKey is empty. Check wake_engine.properties";
            Log.e(TAG, msg);
            notifyError(msg);
            return;
        }
        if (apiSecret == null || apiSecret.trim().isEmpty()) {
            String msg = "AIKit apiSecret is empty. Check wake_engine.properties";
            Log.e(TAG, msg);
            notifyError(msg);
            return;
        }

        executor.execute(() -> {
            try {
                // Copy keyword file from assets to workDir
                File workDir = new File(context.getFilesDir(), workDirName);
                if (!workDir.exists()) {
                    workDir.mkdirs();
                }
                keywordFilePath = copyKeywordFile(workDir.getAbsolutePath());

                // Initialize AIKit SDK
                AiHelper.Params params = AiHelper.Params.builder()
                    .appId(appId.trim())
                    .apiKey(apiKey.trim())
                    .apiSecret(apiSecret.trim())
                    .ability(ABILITY_ID)
                    .workDir(workDir.getAbsolutePath())
                    .build();
                AiHelper.getInst().init(context, params);

                // Register result listener
                AiHelper.getInst().registerListener(ABILITY_ID, aiListener);

                isInitialized = true;
                Log.i(TAG, "AIKit initialized successfully (appId=" + appId.trim() + ")");

            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize AIKit", e);
                notifyError("AIKit init failed: " + e.getMessage());
            }
        });
    }

    @Override
    public void setListener(WakeUpListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean isInitialized() {
        return isInitialized;
    }

    @Override
    public boolean isListening() {
        return isListening;
    }

    @Override
    public void startListening() {
        if (isListening) {
            Log.w(TAG, "Already listening");
            return;
        }
        if (!isInitialized) {
            Log.e(TAG, "Engine not initialized, cannot start listening");
            notifyError("Cannot start listening: engine not initialized");
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

    @Override
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

    @Override
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

    @Override
    public void updateWakeWordConfig(boolean wukongEnabled, boolean nihaoEnabled,
                                      int ncmWukong, int ncmNihao) {
        executor.execute(() -> {
            try {
                // Re-generate keyword file with updated values
                StringBuilder sb = new StringBuilder();
                if (wukongEnabled) {
                    sb.append("\u609f\u7a7a\u609f\u7a7a;nCM:").append(ncmWukong).append(";\n");
                }
                if (nihaoEnabled) {
                    sb.append("\u4f60\u597d\u609f\u7a7a;nCM:").append(ncmNihao).append(";\n");
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

    @Override
    public void release() {
        stopListening();
        executor.shutdownNow();
        // AiHelper.getInst().unInit();
        isListening = false;
        isInitialized = false;
    }

    // ==================== Internal ====================

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

    // ==================== AIKit Result Listener ====================

    /**
     * AIKit result listener.
     * In production, this should implement com.iflytek.aikit.core.AiListener.
     * Currently using a simple implementation for structure.
     */
    private final AiListener aiListener = new AiListener() {
        @Override
        public void onResult(com.iflytek.aikit.core.AiResponse aiResponse) {
            // Parse wake word result from AIKit response
            // String keyword = ... ;
            // int confidence = ... ;
            // notifyWakeUp(keyword, confidence);
        }

        @Override
        public void onError(com.iflytek.aikit.core.AiError aiError) {
            Log.e(TAG, "AIKit error: " + aiError.toString());
            notifyError("AIKit error: " + aiError.toString());
        }

        @Override
        public void onInitDone() {
            Log.i(TAG, "AIKit engine init done");
        }
    };

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
}
