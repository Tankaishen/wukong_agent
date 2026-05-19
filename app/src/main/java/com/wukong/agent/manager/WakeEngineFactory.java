package com.wukong.agent.manager;

import com.wukong.agent.interfaces.IWakeUpEngine;

/**
 * Factory for creating wake engine instances based on type string.
 *
 * Supported types: "aikit", "picovoice", "baidu"
 *
 * To add a new engine:
 * 1. Create XxxWakeEngine implements IWakeUpEngine
 * 2. Add a case here
 * 3. Add credential entries in assets/wake_engine.properties
 */
public class WakeEngineFactory {

    public static final String ENGINE_AIKIT = "aikit";
    public static final String ENGINE_PICOVOICE = "picovoice";
    public static final String ENGINE_BAIDU = "baidu";

    public static IWakeUpEngine create(String engineType) {
        switch (engineType) {
            case ENGINE_AIKIT:
                return new AikitWakeEngine();
            case ENGINE_PICOVOICE:
                // TODO: return new PicovoiceWakeEngine();
                throw new IllegalArgumentException(
                    "Picovoice engine not yet implemented");
            case ENGINE_BAIDU:
                // TODO: return new BaiduWakeEngine();
                throw new IllegalArgumentException(
                    "Baidu engine not yet implemented");
            default:
                throw new IllegalArgumentException(
                    "Unknown wake engine type: " + engineType);
        }
    }
}
