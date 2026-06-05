package com.wukong.agent.util;

public class RetryUtils {

    public static void waitFor(int retryCount, long RETRY_BASE_DELAY_MS){
        long delay = RETRY_BASE_DELAY_MS * (1L << (retryCount - 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void waitFor(long delay){
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
