package com.wukong.agent.mock;

/**
 * TestRobotSDK provides mock implementations of robot SDK classes
 * for unit testing without requiring the actual hardware or AAR.
 */
public class TestRobotSDK {

    private static boolean mockInitialized = false;
    private static String mockRobotState = "active";

    public static void setMockInitialized(boolean initialized) {
        mockInitialized = initialized;
    }

    public static boolean isMockInitialized() {
        return mockInitialized;
    }

    public static void setMockRobotState(String state) {
        mockRobotState = state;
    }

    public static String getMockRobotState() {
        return mockRobotState;
    }

    /**
     * Simulate WkSdk.init()
     */
    public static void initMockSdk() {
        mockInitialized = true;
        mockRobotState = "active";
    }

    /**
     * Reset all mock state
     */
    public static void reset() {
        mockInitialized = false;
        mockRobotState = "active";
    }
}
