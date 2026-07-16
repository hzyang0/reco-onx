package com.interview.minireco.degradation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DegradationDecision {
    private final DegradationLevel level;
    private final int userBucket;
    private final boolean degraded;
    private final int originalLimit;
    private final int effectiveLimit;
    private final List<String> skippedRecallSources;

    public DegradationDecision(
            DegradationLevel level,
            int userBucket,
            boolean degraded,
            int originalLimit,
            int effectiveLimit,
            List<String> skippedRecallSources
    ) {
        this.level = level;
        this.userBucket = userBucket;
        this.degraded = degraded;
        this.originalLimit = originalLimit;
        this.effectiveLimit = effectiveLimit;
        this.skippedRecallSources = List.copyOf(skippedRecallSources);
    }

    public static DegradationDecision none(int userBucket, int limit) {
        return new DegradationDecision(DegradationLevel.NONE, userBucket, false, limit, limit, List.of());
    }

    public DegradationLevel getLevel() {
        return level;
    }

    public int getUserBucket() {
        return userBucket;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public int getOriginalLimit() {
        return originalLimit;
    }

    public int getEffectiveLimit() {
        return effectiveLimit;
    }

    public List<String> getSkippedRecallSources() {
        return skippedRecallSources;
    }

    public boolean shouldSkipRecallSource(String source) {
        return source != null && skippedRecallSources.contains(source);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("level", level.name());
        data.put("userBucket", userBucket);
        data.put("degraded", degraded);
        data.put("originalLimit", originalLimit);
        data.put("effectiveLimit", effectiveLimit);
        data.put("skippedRecallSources", skippedRecallSources);
        return data;
    }
}
