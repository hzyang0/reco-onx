package com.interview.minireco.util;

public final class SimulatedLatency {
    private SimulatedLatency() {
    }

    public static void sleepMs(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while simulating downstream latency", e);
        }
    }
}
