package com.interview.minireco.resilience;

import java.util.LinkedHashMap;
import java.util.Map;

public record ResilienceConfig(
        long timeoutMs,
        int maxRetries,
        int failureThreshold,
        long openDurationMs,
        int threadPoolSize,
        int queueCapacity
) {
    public ResilienceConfig {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        if (maxRetries < 0 || maxRetries > 3) {
            throw new IllegalArgumentException("maxRetries must be between 0 and 3");
        }
        if (failureThreshold <= 0 || openDurationMs <= 0) {
            throw new IllegalArgumentException("circuit breaker values must be positive");
        }
        if (threadPoolSize <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("bulkhead values must be positive");
        }
    }

    public static ResilienceConfig recallDefaults() {
        return new ResilienceConfig(envLong("RECALL_TIMEOUT_MS", 80), 1, 2, 3_000, 2, 8);
    }

    private static long envLong(String name, long defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) { return defaultValue; }
        long value = Long.parseLong(raw);
        if (value <= 0) { throw new IllegalArgumentException(name + " must be positive"); }
        return value;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timeoutMs", timeoutMs);
        data.put("maxRetries", maxRetries);
        data.put("failureThreshold", failureThreshold);
        data.put("openDurationMs", openDurationMs);
        data.put("threadPoolSize", threadPoolSize);
        data.put("queueCapacity", queueCapacity);
        return data;
    }
}
