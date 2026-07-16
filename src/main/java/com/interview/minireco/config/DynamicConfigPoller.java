package com.interview.minireco.config;

import com.interview.minireco.degradation.DegradationManager;
import com.interview.minireco.migration.RolloutManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class DynamicConfigPoller implements AutoCloseable {
    private final ConfigFetcher fetcher;
    private final RolloutManager rolloutManager;
    private final DegradationManager degradationManager;
    private final Clock clock;
    private final long staleAfterMs;
    private final AtomicLong appliedVersion = new AtomicLong(-1);
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>("none");
    private ScheduledExecutorService executor;

    public DynamicConfigPoller(
            ConfigFetcher fetcher,
            RolloutManager rolloutManager,
            DegradationManager degradationManager,
            long staleAfterMs
    ) {
        this(fetcher, rolloutManager, degradationManager, staleAfterMs, Clock.systemUTC());
    }

    DynamicConfigPoller(ConfigFetcher fetcher, RolloutManager rolloutManager,
                        DegradationManager degradationManager, long staleAfterMs, Clock clock) {
        this.fetcher = fetcher;
        this.rolloutManager = rolloutManager;
        this.degradationManager = degradationManager;
        this.staleAfterMs = staleAfterMs;
        this.clock = clock;
    }

    public synchronized void start(long intervalMs) {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dynamic-config-poller");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::pollSafely, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    public boolean pollOnce() throws Exception {
        RuntimeConfigSnapshot snapshot = fetcher.fetch();
        long previous = appliedVersion.get();
        if (snapshot.version() > previous) {
            rolloutManager.update(snapshot.newPipelinePercent(), snapshot.shadowPercent());
            degradationManager.setLevel(snapshot.degradationLevel());
            appliedVersion.set(snapshot.version());
        }
        lastSuccessAt.set(Instant.now(clock));
        lastError.set("none");
        successCount.incrementAndGet();
        return snapshot.version() > previous;
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (Exception e) {
            errorCount.incrementAndGet();
            lastError.set(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public Map<String, Object> snapshot() {
        Instant success = lastSuccessAt.get();
        long ageMs = success == null ? -1 : Math.max(0, Duration.between(success, Instant.now(clock)).toMillis());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", true);
        map.put("appliedVersion", appliedVersion.get());
        map.put("status", success == null ? "STARTING" : ageMs > staleAfterMs ? "STALE" : "HEALTHY");
        map.put("lastSuccessAt", success == null ? "never" : success.toString());
        map.put("lastSuccessAgeMs", ageMs);
        map.put("successCount", successCount.get());
        map.put("errorCount", errorCount.get());
        map.put("lastError", lastError.get());
        map.put("lastKnownGood", Map.of(
                "newPipelinePercent", rolloutManager.getNewPipelinePercent(),
                "shadowPercent", rolloutManager.getShadowPercent(),
                "degradationLevel", degradationManager.getLevel().name()
        ));
        return map;
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
