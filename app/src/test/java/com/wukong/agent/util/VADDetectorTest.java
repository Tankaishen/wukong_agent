package com.wukong.agent.util;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class VADDetectorTest {

    private VADDetector vadDetector;
    private VADDetector.VADListener mockListener;

    @Before
    public void setUp() {
        vadDetector = new VADDetector(500, 1000);
        mockListener = mock(VADDetector.VADListener.class);
        vadDetector.setListener(mockListener);
    }

    @Test
    public void testInitialStateNotSpeaking() {
        assertFalse(vadDetector.isSpeaking());
    }

    @Test
    public void testSilentAudioDoesNotTriggerSpeech() {
        // Create silent audio (all zeros)
        byte[] silentAudio = new byte[3200];
        vadDetector.processAudioChunk(silentAudio, 100);
        assertFalse(vadDetector.isSpeaking());
    }

    @Test
    public void testLoudAudioTriggersSpeechStart() {
        // Create loud audio (high amplitude)
        byte[] loudAudio = new byte[3200];
        for (int i = 0; i < loudAudio.length; i += 2) {
            short sample = 10000;
            loudAudio[i] = (byte) (sample & 0xFF);
            loudAudio[i + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        vadDetector.processAudioChunk(loudAudio, 100);
        assertTrue(vadDetector.isSpeaking());
    }

    @Test
    public void testReset() {
        // Start with loud audio
        byte[] loudAudio = new byte[3200];
        for (int i = 0; i < loudAudio.length; i += 2) {
            short sample = 10000;
            loudAudio[i] = (byte) (sample & 0xFF);
            loudAudio[i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        vadDetector.processAudioChunk(loudAudio, 100);
        assertTrue(vadDetector.isSpeaking());

        vadDetector.reset();
        assertFalse(vadDetector.isSpeaking());
    }

    @Test
    public void testSetEnergyThreshold() {
        vadDetector.setEnergyThreshold(1000);
        // Create medium audio
        byte[] mediumAudio = new byte[3200];
        for (int i = 0; i < mediumAudio.length; i += 2) {
            short sample = 5000;
            mediumAudio[i] = (byte) (sample & 0xFF);
            mediumAudio[i + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        // With high threshold, should not trigger
        vadDetector.processAudioChunk(mediumAudio, 100);
        assertFalse(vadDetector.isSpeaking());
    }
}
