package com.wukong.agent.mock;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import java.io.IOException;

/**
 * MockWebSocketServer for integration testing.
 * Provides a simple mock WebSocket server that responds to chat messages
 * with predefined TTS responses.
 */
public class MockWebSocketServer {

    private MockWebServer server;
    private int port;

    public MockWebSocketServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        server = new MockWebServer();
        server.start(port);
    }

    public void enqueueResponse(MockResponse response) {
        server.enqueue(response);
    }

    public String getUrl() {
        return server.url("/").toString();
    }

    public void shutdown() throws IOException {
        if (server != null) {
            server.shutdown();
        }
    }

    public RecordedRequest takeRequest() throws InterruptedException {
        return server.takeRequest();
    }
}
