package com.wukong.agent.manager;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.iflytek.aikit.core.AiAudio;
import com.iflytek.aikit.core.AiHandle;
import com.iflytek.aikit.core.AiHelper;
import com.iflytek.aikit.core.AiListener;
import com.iflytek.aikit.core.AiRequest;
import com.iflytek.aikit.core.AiResponse;
import com.iflytek.aikit.core.AiStatus;
import com.iflytek.aikit.core.BaseLibrary;
import com.iflytek.aikit.core.CoreListener;
import com.iflytek.aikit.core.ErrType;
import com.wukong.agent.customException.CustomException;
import com.wukong.agent.interfaces.IWakeUpEngine;

/**
 * AIKit (iFlytek) wake word engine implementation for production use.
 *
 * Call flow (from iFlytek sample):
 *   SDK init (BaseLibrary.Params + initEntry)
 *   → registerListener(ABILITY_ID, aiListener)
 *   → startListening: loadData → specifyDataSet → start
 *   → feedAudioData: write audio with AiStatus
 *   → stopListening: end
 *   → release: unInit
 *
 * Resource requirements:
 *   - IVW model files must exist in workDir/ivw/:
 *     IVW_FILLER_1, IVW_GRAM_1, IVW_KEYWORD_1, IVW_MLP_1
 *   - These are copied from assets/xunfeiResource/ivw/ at init time
 *
 * Keyword file format (no nCM in file, threshold set via param):
 *   悟空悟空;
 *   你好悟空;
 */
public class AikitWakeEngine implements IWakeUpEngine {

    private static final String TAG = "AikitWakeEngine";

    /** AIKit IVW70 ability ID */
    private static final String ABILITY_ID = "e867a88f2";

    /** Assets path for IVW model files */
    private static final String IVW_ASSET_DIR = "aikit_resources";

    /** Assets path for keyword file template */
    private static final String KEYWORD_ASSET_PATH = "aikit_resources/keyword1.txt";

    /** Subdirectory under workDir for IVW resources */
//    private static final String IVW_SUBDIR = "ivw";

    // Credential keys expected in the credentials Map
    private static final String KEY_APP_ID = "appId";
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_API_SECRET = "apiSecret";
    private static final String KEY_WORK_DIR = "workDir"; // 不能改，这是一个key值，不是直接拿来用的
    private static final String DEFAULT_WORK_DIR = "aikit_resources";
    private String RES_DIR;

    // Audio constants (16bit 16kHz mono)
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;
    /** Recommended write size: ~40ms of audio at 16kHz 16bit mono = 1280 bytes */
    private static final int WRITE_CHUNK_SIZE = 1280;

    private Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private WakeUpListener listener;

    private boolean isInitialized = false;
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private final AtomicBoolean firstFrameSent = new AtomicBoolean(false);
    private AiHandle aiHandle;
    private String workDirPath;   // absolute path of workDir
    private String keywordFilePath; // absolute path of keyword.txt on disk

    // nCM threshold values (default: 悟空悟空 1200, 你好悟空 1450)
    private int ncmWukong = 1200;
    private int ncmNihao = 1450;
    private boolean wukongEnabled = true;
    private boolean nihaoEnabled = true;
    // Tracks whether keyword file needs rewriting (true on first init or config change)
    private volatile boolean keywordDirty = true;

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
                && credentials.get(KEY_WORK_DIR) != null
                && !credentials.get(KEY_WORK_DIR).isEmpty()
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
                // 1. Prepare workDir
                File baseWorkDir = new File(context.getFilesDir(), workDirName);
//                File ivwWorkDir = new File(baseWorkDir, "ivw"); // SDK实际需要的目录
                Log.d(TAG, "基础工作目录: " + baseWorkDir.getAbsolutePath());
//                Log.d(TAG, "IVW工作目录: " + ivwWorkDir.getAbsolutePath());
                // 创建所有必要的目录（包括子目录）
                if (!baseWorkDir.exists()) {
                    boolean dirsCreated = baseWorkDir.mkdirs(); // 创建多级目录
                    if (!dirsCreated) {
                        String msg = "无法创建IVW工作目录: " + baseWorkDir.getAbsolutePath();
                        Log.e(TAG, msg);
                        notifyError(msg);
                        return;
                    }
                    Log.d(TAG, "成功创建IVW工作目录");
                }
                // 检查目录是否可写（重要）
                if (!baseWorkDir.canWrite()) {
                    String msg = "IVW工作目录不可写: " + baseWorkDir.getAbsolutePath();
                    Log.e(TAG, msg);
                    notifyError(msg);
                    return;
                }
                workDirPath = baseWorkDir.getAbsolutePath();
                Log.i(TAG, "Aikit work directory: " + workDirPath);

                // 2. Copy IVW model resources from assets to workDir/
//                copyIvwResources(workDirPath);

                // 3. Generate keyword file in workDir/
//                keywordFilePath = generateKeywordFile();
//                Log.d(TAG,"keywordFilePath: " + keywordFilePath);

                // 4. Initialize AIKit SDK (use BaseLibrary.Params + initEntry as per sample)
                BaseLibrary.Params params = BaseLibrary.Params.builder()
                    .appId(appId.trim())
                    .apiKey(apiKey.trim())
                    .apiSecret(apiSecret.trim())
                    .workDir(workDirPath)
                    .build();
//                AiHelper.Params params = AiHelper.Params.builder()
//                        .appId(appId.trim())
//                        .apiKey(apiKey.trim())
//                        .apiSecret(apiSecret.trim())
//                        .ability("e867a88f2")
//                        .workDir(workDirPath)//SDK工作路径，这里为绝对路径，此处仅为示例
//                        .build();
                AiHelper.getInst().registerListener(coreListener);
                AiHelper.getInst().initEntry(context, params);

                // 5. Register IVW ability listener
                AiHelper.getInst().registerListener(ABILITY_ID, aiListener);

                isInitialized = true;
                Log.i(TAG, "AIKit SDK initialized (appId=" + appId.trim() + ")");

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
        return isListening.get();
    }

    @Override
    public void startListening() {
        if (isListening.get()) {
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
                // Step 1: Write keyword file to disk
                if (!keyword2File()) {
                    notifyError("Failed to write keyword file");
                    return;
                }

                // Step 2: Load keyword data into engine
                AiRequest.Builder customBuilder = AiRequest.builder();
                customBuilder.customText("key_word", keywordFilePath, 0);
                int ret = AiHelper.getInst().loadData(ABILITY_ID, customBuilder.build());
                if (ret != 0) {
                    Log.e(TAG, "loadData failed: " + ret);
                    notifyError("IVW loadData failed: " + ret);
                    return;
                }
                Log.i(TAG, "loadData success");

                // Step 3: Specify data set
                int[] indexes = {0};
                ret = AiHelper.getInst().specifyDataSet(ABILITY_ID, "key_word", indexes);
                if (ret != 0) {
                    Log.e(TAG, "specifyDataSet failed: " + ret);
                    notifyError("IVW specifyDataSet failed: " + ret);
                    return;
                }
                Log.i(TAG, "specifyDataSet success");

                // Step 4: Start engine session with parameters
                AiRequest.Builder paramBuilder = AiRequest.builder();
                // nCM threshold format: "0 0:threshold" for first keyword
                // Multiple keywords: "0 0:threshold1 1:threshold2"
//                String ncmParam = buildNcmThresholdParam();
//                String ncmParam = "";
//                paramBuilder.param("wdec_param_nCmThreshold", ncmParam);
                paramBuilder.param("wdec_param_nCmThreshold", "0 0:800");
                paramBuilder.param("gramLoad", true);

                aiHandle = AiHelper.getInst().start(ABILITY_ID, paramBuilder.build(), null);
                if (aiHandle.getCode() != 0) {
                    Log.e(TAG,"IVW start failed: " + aiHandle.getCode());
                    notifyError("IVW start failed: " + aiHandle.getCode());
                    return;
                }

                isListening.set(true);
                firstFrameSent.set(false); // Reset for this listening session
//                Log.i(TAG, "Started listening for wake words (nCmThreshold=" + ncmParam + ")");
                Log.i(TAG, "Started listening for wake words");

            } catch (Exception e) {
                Log.e(TAG, "Failed to start listening", e);
                notifyError("Start listening failed: " + e.getMessage());
            }
        });
    }

//    @Override
//    public void stopListening() {
//        if (!isListening.get()) return;
//
//        executor.execute(() -> {
//            try {
//                if (aiHandle != null) {
//                    // Step 1: Send END frame to signal audio stream completion
//                    AiAudio endAudio = AiAudio.get("wav")
//                        .data(new byte[0])
//                        .status(AiStatus.END)
//                        .valid();
//
//                    AiRequest.Builder endBuilder = AiRequest.builder();
//                    endBuilder.payload(endAudio);
//                    int writeRet = AiHelper.getInst().write(endBuilder.build(), aiHandle);
//                    if (writeRet != 0) {
//                        Log.w(TAG, "END frame write() returned: " + writeRet);
//                    } else {
//                        Log.d(TAG, "END frame sent successfully");
//                    }
//
//                    // Step 2: End the engine session
//                    int ret = AiHelper.getInst().end(aiHandle);
//                    if (ret == 0) {
//                        Log.i(TAG, "Stopped listening (end success)");
//                    } else {
//                        Log.w(TAG, "end() returned: " + ret);
//                    }
//                    aiHandle = null;
//                }
//                isListening.set(false);
//                firstFrameSent.set(false); // Reset for next startListening cycle
//            } catch (Exception e) {
//                Log.e(TAG, "Failed to stop listening", e);
//                isListening.set(false);
//                firstFrameSent.set(false);
//            }
//        });
//    }

    @Override
    public void stopListening() {
        if (!isListening.compareAndSet(true, false)) return;
        try {
            if (aiHandle != null) {
                // Step 1: Send END frame to signal audio stream completion
                AiAudio endAudio = AiAudio.get("wav").data(new byte[0]).status(AiStatus.END).valid();

                AiRequest.Builder endBuilder = AiRequest.builder();
                endBuilder.payload(endAudio);
                int writeRet = AiHelper.getInst().write(endBuilder.build(), aiHandle);
                if (writeRet != 0) {
                    Log.w(TAG, "END frame write() returned: " + writeRet + " (session may already be closed)");
                } else {
                    Log.d(TAG, "END frame sent successfully");
                }
                // Step 2: End the engine session
                int ret = AiHelper.getInst().end(aiHandle);
                if (ret == 0) {
                    Log.i(TAG, "Stopped listening (end success)");
                } else {
                    Log.w(TAG, "end() returned: " + ret);
                }
                aiHandle = null;
            }
//            isListening.set(false);
            firstFrameSent.set(false); // Reset for next startListening cycle
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop listening", e);
            isListening.set(false);
            firstFrameSent.set(false);
        }
    }

    @Override
    public void feedAudioData(byte[] pcmData) {
        if (!isListening.get() || aiHandle == null) {
            Log.w(TAG, "Cannot feed audio: not listening or aiHandle is null");
            return;
        }

        executor.execute(() -> {
            try {
                // First frame must be marked BEGIN, subsequent frames use CONTINUE
                AiStatus status = firstFrameSent.compareAndSet(false, true)
                        ? AiStatus.BEGIN
                        : AiStatus.CONTINUE;

                AiAudio aiAudio = AiAudio.get("wav")
                    .data(pcmData)
                    .status(status)
                    .valid();

                AiRequest.Builder dataBuilder = AiRequest.builder();
                dataBuilder.payload(aiAudio);

                int ret = AiHelper.getInst().write(dataBuilder.build(), aiHandle);
                if (ret != 0) {
                    Log.w(TAG, "write() returned: " + ret);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error feeding audio data", e);
            }
        });
    }

    @Override
    public void updateWakeWordConfig(boolean wukongEnabled, boolean nihaoEnabled,
                                      int ncmWukong, int ncmNihao) {
        this.wukongEnabled = wukongEnabled;
        this.nihaoEnabled = nihaoEnabled;
        this.ncmWukong = ncmWukong;
        this.ncmNihao = ncmNihao;
        this.keywordDirty = true;

        Log.i(TAG, "Wake word config updated: wukong=" + wukongEnabled +
              " ncm=" + ncmWukong + ", nihao=" + nihaoEnabled + " ncm=" + ncmNihao);

        // Note: changes take effect on next startListening() call.
        // AIKit does not support hot-reloading keyword config while engine is running.
    }

    @Override
    public void release() {
        stopListening();
        executor.shutdownNow();
        try {
            AiHelper.getInst().unInit();
        } catch (Exception e) {
            Log.w(TAG, "unInit exception (may be expected if not initialized)", e);
        }
        isListening.set(false);
        isInitialized = false;
    }

    // ==================== Internal: Keyword File ====================

    /**
     * Write keyword file to disk based on current config.
     * Format (per iFlytek sample): one keyword per line, keyword followed by semicolon.
     * No nCM in the file — thresholds are set via start param.
     */
    private boolean keyword2File() {
        try {
            try{
                keywordFilePath = generateKeywordFile();
                Log.d(TAG,"Func: keyword2File, keywordFilePath: " + keywordFilePath);
            }  catch (CustomException e){
                e.printStackTrace();
                return false;
            }
            File keywordFile = new File(keywordFilePath);
            Log.d(TAG, "keyword2File: 准备创建文件 " + keywordFile.getAbsolutePath());
            // Skip rewrite if file already exists and config hasn't changed
            if (keywordFile.exists() && !keywordDirty) {
                Log.i(TAG, "Keyword file already exists and config unchanged, skip rewrite");
                return true;
            }

            // Delete existing files when rewrite is needed
            if (keywordFile.exists()) {
                keywordFile.delete();
            }
            // Also clean up the .bin cache file that AIKit generates
            File binFile = new File(keywordFilePath + ".bin");
            if (binFile.exists()) {
                binFile.delete();
            }

            boolean fileCreated = keywordFile.createNewFile();
            if (!fileCreated) {
                Log.e(TAG, "keyword2File: 创建keyword.txt失败");
                return false;
            }

            OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(keywordFile), "UTF-8");
            if (wukongEnabled) {
                writer.write("悟空悟空;");
                writer.write("\n");
                Log.d(TAG, "keyword2File: 写入唤醒词: 悟空悟空");
            }
            if (nihaoEnabled) {
                writer.write("你好悟空;");
                writer.write("\n");
                Log.d(TAG, "keyword2File: 写入唤醒词: 你好悟空");
            }
            writer.close();

            // Print written file content for debugging
            try (BufferedReader reader = new BufferedReader(new FileReader(keywordFile))) {
                String line;
                Log.d(TAG, "--- Start of keyword file content (" + keywordFile.getName() + ") ---");
                while ((line = reader.readLine()) != null) {
                    Log.d(TAG, line);
                }
                Log.d(TAG, "--- End of keyword file content ---");
            } catch (IOException e) {
                Log.w(TAG, "Failed to read back keyword file for logging", e);
            }

            keywordDirty = false;
            Log.i(TAG, "Keyword file written: " + keywordFilePath);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write keyword file", e);
            return false;
        }
    }

    /**
     * Build the nCmThreshold parameter string.
     * Format: "0 0:threshold0 1:threshold1" (index 0 = first keyword, etc.)
     *
     * Example with both keywords enabled:
     *   "0 0:1200 1:1450"
     * Example with only wukong:
     *   "0 0:1200"
     */
    private String buildNcmThresholdParam() {
        StringBuilder sb = new StringBuilder("0");
        int index = 0;
        if (wukongEnabled) {
            sb.append(" ").append(index).append(":").append(ncmWukong);
            index++;
        }
        if (nihaoEnabled) {
            sb.append(" ").append(index).append(":").append(ncmNihao);
        }
        return sb.toString();
    }

    /**
     * Generate initial keyword file path (workDir/keyword.txt).
     */
    private String generateKeywordFile() throws CustomException {
        RES_DIR = workDirPath + "/ivw";
        File resDir = new File(RES_DIR);
        if (!resDir.exists()) {
            boolean dirCreated = resDir.mkdirs(); // 创建多级目录
            if (!dirCreated) {
                Log.e(TAG, "keyword2File: 无法创建目录 " + RES_DIR);
                Log.e(TAG, "请检查RES_DIR路径是否正确，或使用应用内部存储目录");
                throw new CustomException("generateKeywordFile: 无法创建目录" + RES_DIR);
            }
            Log.d(TAG, "keyword2File: 成功创建目录 " + RES_DIR);
        }
        if (!resDir.canWrite()) {
            Log.e(TAG, "keyword2File: 目录不可写 " + RES_DIR);
            throw new CustomException("generateKeywordFile: 目录不可写" + RES_DIR);
        }
        return new File(RES_DIR, "keyword.txt").getAbsolutePath();
    }

    // ==================== Internal: Resource Copy ====================

    /**
     * Copy IVW model resources from assets/xunfeiResource/ivw/ to workDir/.
     * These model files (IVW_FILLER_1, IVW_GRAM_1, IVW_KEYWORD_1, IVW_MLP_1)
     * are required by the AIKit IVW engine.
     */
    private void copyIvwResources(String workDir) throws IOException {
        File destDir = new File(workDir);
        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        AssetManager assets = context.getAssets();
        String[] files = assets.list(IVW_ASSET_DIR);
        if (files == null || files.length == 0) {
            Log.w(TAG, "No IVW resources found in assets/" + IVW_ASSET_DIR);
            return;
        }

        for (String fileName : files) {
            String assetPath = IVW_ASSET_DIR + "/" + fileName;
            Log.d(TAG, "assetPath: " + assetPath);
            File destFile = new File(destDir, fileName);
            Log.d(TAG,"destFile: " + destDir + " " +  fileName);

            // Skip if already exists and not a directory
            if (destFile.exists() && !destFile.isDirectory()) {
                // Check if we need to update (compare size)
                InputStream is = assets.open(assetPath);
                long assetSize = is.available();
                is.close();
                if (destFile.length() == assetSize) {
                    continue; // Already copied, skip
                }
            }

            // Handle subdirectories (like AudioCache)
            String[] subFiles = assets.list(assetPath);
            if (subFiles != null && subFiles.length > 0) {
                // It's a directory, copy recursively
                copyAssetDir(assets, assetPath, destFile);
            } else {
                // It's a file, copy it
                copyAssetFile(assets, assetPath, destFile);
            }
        }

        Log.i(TAG, "IVW resources copied to " + destDir.getAbsolutePath());
    }

    /**
     * Recursively copy an asset directory.
     */
    private void copyAssetDir(AssetManager assets, String assetDir, File destDir) throws IOException {
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        String[] files = assets.list(assetDir);
        if (files == null) return;

        for (String fileName : files) {
            String childAssetPath = assetDir + "/" + fileName;
            File childDest = new File(destDir, fileName);

            String[] subFiles = assets.list(childAssetPath);
            Log.d(TAG,"childAssetPath: " + childAssetPath);
            if (subFiles != null && subFiles.length > 0) {
                copyAssetDir(assets, childAssetPath, childDest);
            } else {
                copyAssetFile(assets, childAssetPath, childDest);
            }
        }
    }

    /**
     * Copy a single asset file to destination.
     */
    private void copyAssetFile(AssetManager assets, String assetPath, File destFile) throws IOException {
        InputStream is = null;
        OutputStream os = null;
        try {
            is = assets.open(assetPath);
            os = new FileOutputStream(destFile);
            byte[] buffer = new byte[4096];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        } finally {
            if (is != null) is.close();
            if (os != null) os.close();
        }
    }

    // ==================== AIKit Listeners ====================

    /**
     * SDK core listener — monitors auth state and init completion.
     */
    private final CoreListener coreListener = new CoreListener() {
        @Override
        public void onAuthStateChange(ErrType type, int code) {
            switch (type) {
                case AUTH:
                    if (code == 0) {
                        Log.i(TAG, "AIKit authorization success");
                    } else {
                        Log.e(TAG, "AIKit authorization failed, code: " + code);
                        notifyError("AIKit auth failed, code: " + code);
                    }
                    break;
                case HTTP:
                    Log.w(TAG, "AIKit HTTP auth result: " + code);
                    break;
                default:
                    Log.w(TAG, "AIKit other event, code: " + code);
                    break;
            }
        }
    };

    /**
     * IVW ability result listener.
     * Callback signature matches iFlytek sample:
     *   onResult(int handleID, List<AiResponse> outputData, Object usrContext)
     *
     * Response parsing:
     *   key = "func_wake_up" → wake word detected
     *   key = "func_pre_wakeup" → pre-wake (low confidence)
     *   value = JSON string with keyword info
     */
    private final AiListener aiListener = new AiListener() {
        @Override
        public void onResult(int handleID, List<AiResponse> outputData, Object usrContext) {
            if (outputData == null || outputData.isEmpty()) return;

            for (AiResponse response : outputData) {
                String key = response.getKey();
                byte[] bytes = response.getValue();
                String result = new String(bytes);

                Log.d(TAG, "IVW onResult: key=" + key + ", value=" + result +
                      ", status=" + response.getStatus());

                if ("func_wake_up".equals(key)) {
                    // Wake word detected!
                    // Parse keyword name from result string
                    String keyword = parseWakeWordKeyword(result);
                    int confidence = parseWakeWordConfidence(result);
                    Log.i(TAG, "Wake word detected: " + keyword + " confidence=" + confidence);
                    notifyWakeUp(keyword, confidence);
                } else if ("func_pre_wakeup".equals(key)) {
                    // Pre-wake detection (lower confidence, optional handling)
                    Log.d(TAG, "Pre-wake detected: " + result);
                }
            }
        }

        @Override
        public void onEvent(int handleID, int event, List<AiResponse> list, Object usrContext) {
            Log.d(TAG, "IVW onEvent: handle=" + handleID + " event=" + event);
        }

        @Override
        public void onError(int handleID, int errCode, String errInfo, Object usrContext) {
            Log.e(TAG, "IVW onError: handle=" + handleID + " code=" + errCode + " info=" + errInfo);
            notifyError("IVW engine error: " + errInfo);
        }
    };

    /**
     * Parse keyword name from AIKit wake result.
     * The result format is typically JSON or structured text from iFlytek.
     */
    private String parseWakeWordKeyword(String result) {
        // iFlytek IVW result typically contains keyword info
        // Common format: {"keyword":"悟空悟空",...} or plain text
        // For robustness, try JSON first, then fallback
        try {
            if (result.contains("\"keyword\"")) {
                // Simple JSON extraction without Gson dependency
                int start = result.indexOf("\"keyword\"") + "\"keyword\"".length();
                // Find the value after colon
                int colonIdx = result.indexOf(":", start);
                if (colonIdx >= 0) {
                    int valueStart = result.indexOf("\"", colonIdx) + 1;
                    int valueEnd = result.indexOf("\"", valueStart);
                    if (valueStart > 0 && valueEnd > valueStart) {
                        return result.substring(valueStart, valueEnd);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse keyword from JSON, using raw result");
        }
        // Fallback: return first few chars or "unknown"
        return result.length() > 20 ? result.substring(0, 20) + "..." : result;
    }

    /**
     * Parse confidence from AIKit wake result.
     */
    private int parseWakeWordConfidence(String result) {
        try {
            if (result.contains("\"confidence\"") || result.contains("\"score\"")) {
                String key = result.contains("\"confidence\"") ? "\"confidence\"" : "\"score\"";
                int start = result.indexOf(key) + key.length();
                int colonIdx = result.indexOf(":", start);
                if (colonIdx >= 0) {
                    int valueStart = colonIdx + 1;
                    // Skip whitespace
                    while (valueStart < result.length() &&
                           (result.charAt(valueStart) == ' ' || result.charAt(valueStart) == '"')) {
                        valueStart++;
                    }
                    int valueEnd = valueStart;
                    while (valueEnd < result.length() &&
                           (Character.isDigit(result.charAt(valueEnd)) ||
                            result.charAt(valueEnd) == '.' || result.charAt(valueEnd) == '-')) {
                        valueEnd++;
                    }
                    if (valueEnd > valueStart) {
                        return (int) Float.parseFloat(result.substring(valueStart, valueEnd));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse confidence from result");
        }
        return -1; // Unknown confidence
    }

    // ==================== Notification Helpers ====================

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
