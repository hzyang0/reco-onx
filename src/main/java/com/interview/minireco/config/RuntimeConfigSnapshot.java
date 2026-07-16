package com.interview.minireco.config;

import com.interview.minireco.degradation.DegradationLevel;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record RuntimeConfigSnapshot(
        long version,
        int newPipelinePercent,
        int shadowPercent,
        DegradationLevel degradationLevel,
        String updatedBy,
        Instant updatedAt
) {
    public RuntimeConfigSnapshot {
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        validatePercent(newPipelinePercent, "newPipelinePercent");
        validatePercent(shadowPercent, "shadowPercent");
        if (degradationLevel == null) {
            throw new IllegalArgumentException("degradationLevel is required");
        }
        if (updatedBy == null || updatedBy.isBlank()) {
            throw new IllegalArgumentException("updatedBy is required");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt is required");
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", version);
        map.put("newPipelinePercent", newPipelinePercent);
        map.put("shadowPercent", shadowPercent);
        map.put("degradationLevel", degradationLevel.name());
        map.put("updatedBy", updatedBy);
        map.put("updatedAt", updatedAt.toString());
        return map;
    }

    private static void validatePercent(int value, String name) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
    }
}
