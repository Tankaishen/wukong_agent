package com.wukong.agent.manager;

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketManagerTest {

    private WebSocketManager manager;

    @Before
    public void setUp() {
        manager = new WebSocketManager();
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.disconnect();
        }
    }

    @Test
    public void testInitialStateNotConnected() {
        assertFalse(manager.isConnected());
    }

    @Test
    public void testSetServerUrl() {
        manager.setServerUrl("wss://localhost:8080/ws");
        // No exception means success
    }

    @Test
    public void testSendWhenNotConnected() {
        boolean result = manager.sendChatMessage("session1", "base64audio", false);
        assertFalse(result);
    }

    @Test
    public void testDisconnectWhenNotConnected() {
        // Should not throw
        manager.disconnect();
        assertFalse(manager.isConnected());
    }
}
