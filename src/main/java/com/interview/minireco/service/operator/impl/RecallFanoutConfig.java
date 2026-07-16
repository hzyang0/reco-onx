package com.interview.minireco.service.operator.impl;

import java.util.LinkedHashMap;
import java.util.Map;

public record RecallFanoutConfig(
        long overallTimeoutMs,
        int parallelism,
        int queueCapacity
) {
    public RecallFanoutConfig {
        if (overallTimeoutMs <= 0) {
            throw new IllegalArgumentException("overallTimeoutMs must be positive");
        }
        if (parallelism <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("fanout pool values must be positive");
        }
    }

    public static RecallFanoutConfig defaults() {
        return new RecallFanoutConfig(120, 12, 100);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("overallTimeoutMs", overallTimeoutMs);
        data.put("parallelism", parallelism);
        data.put("queueCapacity", queueCapacity);
        return data;
    }
}
