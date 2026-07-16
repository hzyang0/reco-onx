package com.interview.minireco.migration;

import com.interview.minireco.degradation.UserLayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class RolloutManager {
    private static final RolloutManager GLOBAL = new RolloutManager(
            envPercent("NEW_PIPELINE_PERCENT", 100),
            envPercent("SHADOW_PERCENT", 0)
    );

    private final AtomicReference<RolloutConfig> config;

    public RolloutManager(int newPipelinePercent, int shadowPercent) {
        validatePercent(newPipelinePercent, "newPipelinePercent");
        validatePercent(shadowPercent, "shadowPercent");
        this.config = new AtomicReference<>(new RolloutConfig(newPipelinePercent, shadowPercent));
    }

    public static RolloutManager global() {
        return GLOBAL;
    }

    public RolloutDecision decide(long userId) {
        int userBucket = UserLayer.bucket(userId);
        int stableShadowBucket = Math.floorMod(Long.hashCode(userId) * 31 + 17, 100);
        RolloutConfig current = config.get();
        PipelineVersion primary = userBucket < current.newPipelinePercent()
                ? PipelineVersion.NEW
                : PipelineVersion.LEGACY;
        return new RolloutDecision(
                primary,
                userBucket,
                stableShadowBucket < current.shadowPercent(),
                stableShadowBucket,
                current.newPipelinePercent(),
                current.shadowPercent()
        );
    }

    public void setNewPipelinePercent(int percent) {
        update(percent, null);
    }

    public void setShadowPercent(int percent) {
        update(null, percent);
    }

    public void update(Integer newPercent, Integer nextShadowPercent) {
        if (newPercent != null) {
            validatePercent(newPercent, "newPipelinePercent");
        }
        if (nextShadowPercent != null) {
            validatePercent(nextShadowPercent, "shadowPercent");
        }
        config.updateAndGet(current -> new RolloutConfig(
                newPercent == null ? current.newPipelinePercent() : newPercent,
                nextShadowPercent == null ? current.shadowPercent() : nextShadowPercent
        ));
    }

    public int getNewPipelinePercent() {
        return config.get().newPipelinePercent();
    }

    public int getShadowPercent() {
        return config.get().shadowPercent();
    }

    public void reset() {
        config.set(new RolloutConfig(100, 0));
    }

    public Map<String, Object> snapshot() {
        RolloutConfig current = config.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("newPipelinePercent", current.newPipelinePercent());
        data.put("legacyPipelinePercent", 100 - current.newPipelinePercent());
        data.put("shadowPercent", current.shadowPercent());
        data.put("routingRule", "userId % 100 < newPipelinePercent");
        return data;
    }

    private static int envPercent(String name, int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw);
            validatePercent(value, name);
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private static void validatePercent(int percent, String name) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
    }

    private record RolloutConfig(int newPipelinePercent, int shadowPercent) {
    }
}
