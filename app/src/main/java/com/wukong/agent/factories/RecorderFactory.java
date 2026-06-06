package com.wukong.agent.factories;

import android.content.Context;

import com.wukong.agent.interfaces.IRecorder;
import com.wukong.agent.recorder.AndroidAudioRecorder;
import com.wukong.agent.recorder.UbtAudioRecorder;

/**
 * Factory for creating recorder instances based on type string.
 *
 * Supported types: "android", "ubt"
 *
 * To add a new recorder:
 * 1. Create XxxRecorder implements IRecorder in recorder/ package
 * 2. Add a constant and case here
 * 3. Add type entry in assets/recorder.properties
 */
public class RecorderFactory {

    public static final String RECORDER_ANDROID = "android";
    public static final String RECORDER_UBT = "ubt";

    /**
     * Create a recorder instance based on the type string.
     *
     * @param recorderType  "android" for AudioRecord, "ubt" for PreProcessedRecorder SDK
     * @param context       Application context (required by UbtAudioRecorder)
     * @return IRecorder instance
     */
    public static IRecorder create(String recorderType, Context context) {
        switch (recorderType) {
            case RECORDER_ANDROID:
                return new AndroidAudioRecorder();
            case RECORDER_UBT:
                return new UbtAudioRecorder(context);
            default:
                throw new IllegalArgumentException(
                    "Unknown recorder type: " + recorderType);
        }
    }
}
