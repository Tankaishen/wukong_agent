package com.wukong.agent.util;

import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AudioUtils {

    private static final String TAG = "AudioUtils";

    /**
     * Convert PCM 16bit byte array to Base64 string
     */
    public static String pcmToBase64(byte[] pcmData) {
//        return Base64.encodeToString(pcmData, Base64.NO_WRAP);
        return pcmToBase64(pcmData, pcmData.length);
    }

    public static String pcmToBase64(byte[] pcmData, int length) {
        // 关键：使用Base64的offset和length参数
        return Base64.encodeToString(pcmData, 0, length, Base64.NO_WRAP);
    }
    /**
     * Convert Base64 string to PCM 16bit byte array
     */
    public static byte[] base64ToPcm(String base64) {
        return Base64.decode(base64, Base64.NO_WRAP);
    }

    /**
     * Extract single channel from 6-channel audio data.
     * 6-channel layout: MIC1(16bit) | MIC2(16bit) | MIC3(16bit) | MIC4(16bit) | REF1(16bit) | REF2(16bit)
     * Each sample = 6 * 2 = 12 bytes
     *
     * @param sixChannelData 6-channel interleaved PCM data
     * @param channelIndex 0-5 (0=MIC1, 1=MIC2, 2=MIC3, 3=MIC4, 4=REF1, 5=REF2)
     * @return Single channel PCM data
     */
    public static byte[] extractChannel(byte[] sixChannelData, int channelIndex) {
        int channels = 6;
        int bytesPerSample = 2; // 16bit
        int frameSize = channels * bytesPerSample; // 12 bytes per frame
        int numFrames = sixChannelData.length / frameSize;
        byte[] singleChannel = new byte[numFrames * bytesPerSample];

        for (int i = 0; i < numFrames; i++) {
            int srcOffset = i * frameSize + channelIndex * bytesPerSample;
            int dstOffset = i * bytesPerSample;
            singleChannel[dstOffset] = sixChannelData[srcOffset];
            singleChannel[dstOffset + 1] = sixChannelData[srcOffset + 1];
        }
        return singleChannel;
    }

    /**
     * Calculate RMS energy of PCM 16bit audio data
     */
    public static double calculateRmsEnergy(byte[] pcmData) {
        if (pcmData == null || pcmData.length == 0) return 0;

        long sum = 0;
        int samples = pcmData.length / 2;
        for (int i = 0; i < samples; i++) {
            short sample = (short) ((pcmData[i * 2] & 0xFF) | (pcmData[i * 2 + 1] << 8));
            sum += (long) sample * sample;
        }
        return Math.sqrt((double) sum / samples);
    }

    /**
     * Calculate peak amplitude of PCM 16bit audio data
     */
    public static short calculatePeakAmplitude(byte[] pcmData) {
        if (pcmData == null || pcmData.length < 2) return 0;

        short peak = 0;
        int samples = pcmData.length / 2;
        for (int i = 0; i < samples; i++) {
            short sample = (short) ((pcmData[i * 2] & 0xFF) | (pcmData[i * 2 + 1] << 8));
            if (Math.abs(sample) > Math.abs(peak)) {
                peak = sample;
            }
        }
        return peak;
    }
}
