package com.interview.minireco.cache;

import com.interview.minireco.service.downstream.UserFeatureService;

import java.util.concurrent.atomic.AtomicBoolean;

public final class FeatureCacheRuntime {
    private static final AtomicBoolean ENABLED = new AtomicBoolean();

    private FeatureCacheRuntime() {
    }

    public static UserFeatureService wrap(UserFeatureService origin) {
        String redisUrl = System.getenv("REDIS_URL");
        if (redisUrl == null || redisUrl.isBlank()) {
            ENABLED.set(false);
            return origin;
        }
        long ttl = positiveEnv("FEATURE_CACHE_TTL_SECONDS", 60);
        long jitter = nonNegativeEnv("FEATURE_CACHE_TTL_JITTER_SECONDS", 15);
        long nullTtl = positiveEnv("FEATURE_CACHE_NULL_TTL_SECONDS", 10);
        RedisFeatureCacheClient client = new RedisFeatureCacheClient(redisUrl);
        ENABLED.set(true);
        Runtime.getRuntime().addShutdownHook(new Thread(client::close, "feature-cache-shutdown"));
        return new CachedUserFeatureService(origin, client, ttl, jitter, nullTtl);
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    private static long positiveEnv(String name, long defaultValue) {
        long value = nonNegativeEnv(name, defaultValue);
        if (value == 0) { throw new IllegalArgumentException(name + " must be positive"); }
        return value;
    }

    private static long nonNegativeEnv(String name, long defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) { return defaultValue; }
        long value = Long.parseLong(raw);
        if (value < 0) { throw new IllegalArgumentException(name + " must not be negative"); }
        return value;
    }
}
