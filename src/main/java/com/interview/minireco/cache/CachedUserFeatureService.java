package com.interview.minireco.cache;

import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.service.downstream.UserFeatureService;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class CachedUserFeatureService implements UserFeatureService {
    static final String NULL_SENTINEL = "__NULL__";

    private final UserFeatureService origin;
    private final FeatureCacheClient cache;
    private final long baseTtlSeconds;
    private final long jitterSeconds;
    private final long nullTtlSeconds;
    private final FeatureCacheStats stats;
    private final MetricsRegistry metrics;
    private final ConcurrentMap<Long, CompletableFuture<UserFeature>> inFlight = new ConcurrentHashMap<>();

    public CachedUserFeatureService(UserFeatureService origin, FeatureCacheClient cache,
                                    long baseTtlSeconds, long jitterSeconds, long nullTtlSeconds) {
        this(origin, cache, baseTtlSeconds, jitterSeconds, nullTtlSeconds,
                FeatureCacheStats.global(), MetricsRegistry.global());
    }

    CachedUserFeatureService(UserFeatureService origin, FeatureCacheClient cache,
                             long baseTtlSeconds, long jitterSeconds, long nullTtlSeconds,
                             FeatureCacheStats stats, MetricsRegistry metrics) {
        this.origin = origin;
        this.cache = cache;
        this.baseTtlSeconds = baseTtlSeconds;
        this.jitterSeconds = jitterSeconds;
        this.nullTtlSeconds = nullTtlSeconds;
        this.stats = stats;
        this.metrics = metrics;
    }

    @Override
    public UserFeature getUserFeature(long userId) {
        String key = "mini-reco:user-feature:v1:" + userId;
        try {
            String cached = cache.get(key);
            if (cached != null) {
                record("hit");
                return NULL_SENTINEL.equals(cached) ? null : decode(cached);
            }
            record("miss");
        } catch (RuntimeException e) {
            stats.error();
            metrics.increment("feature_cache_access", Map.of("result", "error"));
            return loadSingleFlight(userId, key);
        }
        return loadSingleFlight(userId, key);
    }

    private UserFeature loadSingleFlight(long userId, String key) {
        CompletableFuture<UserFeature> leader = new CompletableFuture<>();
        CompletableFuture<UserFeature> existing = inFlight.putIfAbsent(userId, leader);
        if (existing != null) {
            stats.singleFlightJoin();
            metrics.increment("feature_cache_load", Map.of("result", "single_flight_join"));
            try {
                return existing.join();
            } catch (CompletionException e) {
                throw unwrap(e);
            }
        }
        try {
            UserFeature feature = loadOrigin(userId);
            try {
                if (feature == null) {
                    cache.set(key, NULL_SENTINEL, nullTtlSeconds);
                } else {
                    cache.set(key, encode(feature), ttlFor(userId));
                }
                stats.write();
            } catch (RuntimeException e) {
                stats.error();
                metrics.increment("feature_cache_access", Map.of("result", "write_error"));
            }
            leader.complete(feature);
            return feature;
        } catch (RuntimeException e) {
            leader.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(userId, leader);
        }
    }

    private UserFeature loadOrigin(long userId) {
        stats.originLoad();
        metrics.increment("feature_cache_load", Map.of("result", "origin"));
        return origin.getUserFeature(userId);
    }

    private void record(String result) {
        if ("hit".equals(result)) { stats.hit(); } else { stats.miss(); }
        metrics.increment("feature_cache_access", Map.of("result", result));
    }

    private long ttlFor(long userId) {
        return baseTtlSeconds + (jitterSeconds <= 0 ? 0 : Math.floorMod(Long.hashCode(userId), jitterSeconds + 1));
    }

    private String encode(UserFeature feature) {
        return feature.getUserId() + "|" + feature.isNewUser() + "|"
                + feature.getPreferredCategory() + "|" + feature.getAge();
    }

    private UserFeature decode(String value) {
        String[] fields = value.split("\\|", -1);
        if (fields.length != 4) {
            throw new IllegalArgumentException("invalid cached user feature payload");
        }
        return new UserFeature(Long.parseLong(fields[0]), Boolean.parseBoolean(fields[1]),
                fields[2], Integer.parseInt(fields[3]));
    }

    private RuntimeException unwrap(CompletionException e) {
        return e.getCause() instanceof RuntimeException runtime ? runtime : e;
    }
}
