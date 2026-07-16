package com.interview.minireco.resilience;

import com.interview.minireco.domain.Item;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.observability.StructuredLogger;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.RecallService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class ResilientRecallService implements RecallService {
    private static final StructuredLogger LOGGER = StructuredLogger.getLogger(ResilientRecallService.class);

    private final RecallService delegate;
    private final ResilienceConfig config;
    private final CircuitBreaker circuitBreaker;
    private final ThreadPoolExecutor executor;
    private final MetricsRegistry metricsRegistry;

    public ResilientRecallService(RecallService delegate, ResilienceConfig config) {
        this(delegate, config, MetricsRegistry.global());
    }

    public ResilientRecallService(
            RecallService delegate,
            ResilienceConfig config,
            MetricsRegistry metricsRegistry
    ) {
        this.delegate = delegate;
        this.config = config;
        this.metricsRegistry = metricsRegistry;
        this.circuitBreaker = new CircuitBreaker(config.failureThreshold(), config.openDurationMs());
        this.executor = new ThreadPoolExecutor(
                config.threadPoolSize(),
                config.threadPoolSize(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity()),
                daemonThreadFactory(delegate.source()),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public String source() {
        return delegate.source();
    }

    @Override
    public List<Item> recall(RecommendContext context) {
        long logicalStart = System.nanoTime();
        if (!circuitBreaker.tryAcquirePermission()) {
            return fallback(context, "circuit_open", 0, elapsedMs(logicalStart), null);
        }

        RuntimeException lastFailure = null;
        int maxAttempts = config.maxRetries() + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long attemptStart = System.nanoTime();
            try {
                List<Item> items = executeOnce(context);
                metricsRegistry.recordTimer(
                        "downstream.call.cost",
                        Map.of("source", source(), "status", "success"),
                        elapsedMs(attemptStart)
                );
                metricsRegistry.increment("downstream.call.success", Map.of("source", source()));
                circuitBreaker.recordSuccess();
                putDebug(context, "SUCCESS", "none", attempt, elapsedMs(logicalStart));
                return items;
            } catch (RuntimeException e) {
                lastFailure = e;
                String reason = failureReason(e);
                metricsRegistry.recordTimer(
                        "downstream.call.cost",
                        Map.of("source", source(), "status", reason),
                        elapsedMs(attemptStart)
                );
                metricsRegistry.increment(
                        "downstream.call.error",
                        Map.of("source", source(), "reason", reason)
                );
                if ("cancelled".equals(reason)) {
                    circuitBreaker.recordFailure();
                    return fallback(context, reason, attempt, elapsedMs(logicalStart), e);
                }
                if (attempt < maxAttempts) {
                    metricsRegistry.increment(
                            "downstream.retry",
                            Map.of("source", source(), "reason", reason)
                    );
                }
            }
        }

        circuitBreaker.recordFailure();
        return fallback(
                context,
                failureReason(lastFailure),
                maxAttempts,
                elapsedMs(logicalStart),
                lastFailure
        );
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", source());
        data.put("config", config.toMap());
        data.put("circuit", circuitBreaker.snapshot());
        data.put("bulkhead", bulkheadSnapshot());
        return data;
    }

    public void resetCircuit() {
        circuitBreaker.reset();
    }

    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    private List<Item> executeOnce(RecommendContext context) {
        Future<List<Item>> future;
        try {
            future = executor.submit(() -> delegate.recall(context));
        } catch (RejectedExecutionException e) {
            throw new BulkheadFullException(source(), e);
        }

        try {
            return future.get(config.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DownstreamTimeoutException(source(), config.timeoutMs());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DownstreamCallException(source(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new DownstreamCallException(source(), cause);
        }
    }

    private List<Item> fallback(
            RecommendContext context,
            String reason,
            int attempts,
            long costMs,
            RuntimeException error
    ) {
        metricsRegistry.increment(
                "downstream.fallback",
                Map.of("source", source(), "reason", reason)
        );
        putDebug(context, "FALLBACK", reason, attempts, costMs);
        LOGGER.warn(context.getRequestId(), "downstream_fallback", () -> Map.of(
                "source", source(),
                "reason", reason,
                "attempts", attempts,
                "costMs", costMs,
                "errorType", error == null ? "none" : error.getClass().getSimpleName()
        ));
        return List.of();
    }

    private void putDebug(RecommendContext context, String status, String reason, int attempts, long costMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("reason", reason);
        data.put("attempts", attempts);
        data.put("costMs", costMs);
        data.put("circuit", circuitBreaker.snapshot());
        data.put("bulkhead", bulkheadSnapshot());
        context.putResilienceDebug(source(), data);
    }

    private Map<String, Object> bulkheadSnapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("poolSize", config.threadPoolSize());
        data.put("activeThreads", executor.getActiveCount());
        data.put("queuedTasks", executor.getQueue().size());
        data.put("queueCapacity", config.queueCapacity());
        return data;
    }

    private String failureReason(RuntimeException error) {
        if (error instanceof DownstreamTimeoutException) {
            return "timeout";
        }
        if (error instanceof BulkheadFullException) {
            return "bulkhead_full";
        }
        if (error instanceof DownstreamCallException && error.getCause() instanceof InterruptedException) {
            return "cancelled";
        }
        return "error";
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private ThreadFactory daemonThreadFactory(String source) {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "recall-" + source + "-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
