package com.wukong.agent.manager;

import org.junit.Test;
import com.wukong.agent.model.RobotConfig;
import static org.junit.Assert.*;

public class ConfigManagerTest {

    @Test
    public void testDefaultConfig() {
        RobotConfig config = new RobotConfig();
        assertEquals("wss://localhost:8080/ws", config.getWsServerUrl());
        assertTrue(config.isWakeWukongEnabled());
        assertTrue(config.isWakeNihaoEnabled());
        assertEquals(1200, config.getNcmWukong());
        assertEquals(1450, config.getNcmNihao());
        assertEquals(80, config.getTtsVolume());
        assertEquals(1500, config.getVadSilenceDurationMs());
        assertEquals(500, config.getVadEnergyThreshold());
        assertEquals(30000, config.getRecordingTimeoutMs());
        assertEquals(10000, config.getProcessingTimeoutMs());
        assertEquals(60000, config.getPlayingTimeoutMs());
        assertEquals("", config.getLlmModelName());
    }

    @Test
    public void testConfigSetters() {
        RobotConfig config = new RobotConfig();
        config.setWsServerUrl("wss://example.com/ws");
        assertEquals("wss://example.com/ws", config.getWsServerUrl());

        config.setWakeWukongEnabled(false);
        assertFalse(config.isWakeWukongEnabled());

        config.setNcmWukong(1500);
        assertEquals(1500, config.getNcmWukong());

        config.setTtsVolume(50);
        assertEquals(50, config.getTtsVolume());
    }
}
