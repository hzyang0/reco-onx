package com.interview.minireco.service.operator.impl;

import com.interview.minireco.domain.Item;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.RecallService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ParallelRecallFanout {
    private final List<RecallService> recallServices;
    private final RecallFanoutConfig config;
    private final MetricsRegistry metricsRegistry;
    private final ThreadPoolExecutor executor;

    public ParallelRecallFanout(
            List<RecallService> recallServices,
            RecallFanoutConfig config,
            MetricsRegistry metricsRegistry
    ) {
        this.recallServices = List.copyOf(recallServices);
        this.config = config;
        this.metricsRegistry = metricsRegistry;
        validateUniqueSources(this.recallServices);
        this.executor = new ThreadPoolExecutor(
                config.parallelism(),
                config.parallelism(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity()),
                daemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public FanoutResult execute(RecommendContext context, List<String> skippedSources) {
        long fanoutStart = System.nanoTime();
        CompletionService<RecallResult> completionService = new ExecutorCompletionService<>(executor);
        Map<Future<RecallResult>, String> futures = new LinkedHashMap<>();
        Map<String, List<Item>> itemsBySource = new LinkedHashMap<>();
        Map<String, Long> costBySource = new LinkedHashMap<>();
        Map<String, String> failedSources = new LinkedHashMap<>();
        Set<String> completedSources = new LinkedHashSet<>();
        Set<String> timedOutSources = new LinkedHashSet<>();
        List<String> submittedSources = new ArrayList<>();

        for (RecallService recallService : recallServices) {
            if (skippedSources.contains(recallService.source())) {
                continue;
            }
            submit(completionService, futures, submittedSources, failedSources, recallService, context);
        }

        collectUntilDeadline(
                completionService,
                futures,
                itemsBySource,
                costBySource,
                failedSources,
                completedSources,
                timedOutSources,
                fanoutStart
        );

        List<Item> items = new ArrayList<>();
        Map<String, Integer> itemCountBySource = new LinkedHashMap<>();
        for (RecallService recallService : recallServices) {
            List<Item> sourceItems = itemsBySource.get(recallService.source());
            if (sourceItems != null) {
                items.addAll(sourceItems);
                itemCountBySource.put(recallService.source(), sourceItems.size());
            }
        }

        List<String> fallbackSources = resilienceFallbackSources(context);
        boolean partialResult = !timedOutSources.isEmpty()
                || !failedSources.isEmpty()
                || !fallbackSources.isEmpty();
        long fanoutCostMs = elapsedMs(fanoutStart);
        String status = partialResult ? "partial" : "success";
        metricsRegistry.recordTimer("recall.fanout.cost", Map.of("status", status), fanoutCostMs);
        metricsRegistry.increment("recall.fanout.request", Map.of("status", status));

        Map<String, Object> debug = fanoutDebug(
                fanoutCostMs,
                submittedSources,
                orderedSources(completedSources),
                orderedSources(timedOutSources),
                skippedSources,
                fallbackSources,
                failedSources,
                costBySource,
                itemCountBySource,
                partialResult
        );
        return new FanoutResult(items, debug);
    }

    private void submit(
            CompletionService<RecallResult> completionService,
            Map<Future<RecallResult>, String> futures,
            List<String> submittedSources,
            Map<String, String> failedSources,
            RecallService recallService,
            RecommendContext context
    ) {
        try {
            Future<RecallResult> future = completionService.submit(() -> recall(recallService, context));
            futures.put(future, recallService.source());
            submittedSources.add(recallService.source());
        } catch (RejectedExecutionException e) {
            failedSources.put(recallService.source(), "fanout_pool_full");
            metricsRegistry.increment(
                    "recall.fanout.failure",
                    Map.of("source", recallService.source(), "reason", "fanout_pool_full")
            );
        }
    }

    private void collectUntilDeadline(
            CompletionService<RecallResult> completionService,
            Map<Future<RecallResult>, String> futures,
            Map<String, List<Item>> itemsBySource,
            Map<String, Long> costBySource,
            Map<String, String> failedSources,
            Set<String> completedSources,
            Set<String> timedOutSources,
            long fanoutStart
    ) {
        long deadlineNanos = fanoutStart + TimeUnit.MILLISECONDS.toNanos(config.overallTimeoutMs());
        Set<Future<RecallResult>> observedFutures = new LinkedHashSet<>();
        while (observedFutures.size() < futures.size()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }

            Future<RecallResult> completedFuture;
            try {
                completedFuture = completionService.poll(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                cancelAll(futures.keySet());
                Thread.currentThread().interrupt();
                throw new IllegalStateException("parallel recall interrupted", e);
            }
            if (completedFuture == null) {
                break;
            }
            observedFutures.add(completedFuture);
            collectResult(
                    completedFuture,
                    futures.get(completedFuture),
                    itemsBySource,
                    costBySource,
                    completedSources,
                    failedSources
            );
        }

        collectFinishedAndCancelRemaining(
                futures,
                observedFutures,
                itemsBySource,
                costBySource,
                completedSources,
                failedSources,
                timedOutSources
        );
    }

    private void collectFinishedAndCancelRemaining(
            Map<Future<RecallResult>, String> futures,
            Set<Future<RecallResult>> observedFutures,
            Map<String, List<Item>> itemsBySource,
            Map<String, Long> costBySource,
            Set<String> completedSources,
            Map<String, String> failedSources,
            Set<String> timedOutSources
    ) {
        for (Map.Entry<Future<RecallResult>, String> entry : futures.entrySet()) {
            Future<RecallResult> future = entry.getKey();
            if (observedFutures.contains(future)) {
                continue;
            }
            if (future.isDone()) {
                collectResult(
                        future,
                        entry.getValue(),
                        itemsBySource,
                        costBySource,
                        completedSources,
                        failedSources
                );
                continue;
            }
            future.cancel(true);
            timedOutSources.add(entry.getValue());
            metricsRegistry.increment("recall.fanout.timeout", Map.of("source", entry.getValue()));
        }
    }

    private RecallResult recall(RecallService recallService, RecommendContext context) {
        long start = System.nanoTime();
        List<Item> items = recallService.recall(context);
        return new RecallResult(recallService.source(), items, elapsedMs(start));
    }

    private void collectResult(
            Future<RecallResult> future,
            String source,
            Map<String, List<Item>> itemsBySource,
            Map<String, Long> costBySource,
            Set<String> completedSources,
            Map<String, String> failedSources
    ) {
        try {
            RecallResult result = future.get();
            itemsBySource.put(result.source(), result.items());
            costBySource.put(result.source(), result.costMs());
            completedSources.add(result.source());
            metricsRegistry.recordTimer(
                    "recall.source.cost",
                    Map.of("source", result.source(), "status", "completed"),
                    result.costMs()
            );
        } catch (InterruptedException e) {
            cancelAll(List.of(future));
            Thread.currentThread().interrupt();
            throw new IllegalStateException("collecting recall result interrupted", e);
        } catch (ExecutionException e) {
            String reason = e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName();
            failedSources.put(source, reason);
            metricsRegistry.increment(
                    "recall.fanout.failure",
                    Map.of("source", source, "reason", "call_error")
            );
        }
    }

    private Map<String, Object> fanoutDebug(
            long costMs,
            List<String> submittedSources,
            List<String> completedSources,
            List<String> timedOutSources,
            List<String> skippedSources,
            List<String> fallbackSources,
            Map<String, String> failedSources,
            Map<String, Long> costBySource,
            Map<String, Integer> itemCountBySource,
            boolean partialResult
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", partialResult ? "PARTIAL" : "SUCCESS");
        data.put("partialResult", partialResult);
        data.put("costMs", costMs);
        data.put("config", config.toMap());
        data.put("submittedSources", submittedSources);
        data.put("completedSources", completedSources);
        data.put("timedOutSources", timedOutSources);
        data.put("degradationSkippedSources", skippedSources);
        data.put("fallbackSources", fallbackSources);
        data.put("failedSources", orderedMap(failedSources));
        data.put("sourceCostMs", orderedMap(costBySource));
        data.put("itemCountBySource", itemCountBySource);
        return data;
    }

    private <T> Map<String, T> orderedMap(Map<String, T> values) {
        Map<String, T> ordered = new LinkedHashMap<>();
        for (RecallService service : recallServices) {
            if (values.containsKey(service.source())) {
                ordered.put(service.source(), values.get(service.source()));
            }
        }
        return ordered;
    }

    private List<String> orderedSources(Set<String> sources) {
        return recallServices.stream()
                .map(RecallService::source)
                .filter(sources::contains)
                .toList();
    }

    private List<String> resilienceFallbackSources(RecommendContext context) {
        Map<String, Object> resilience = context.getResilienceDebugSnapshot();
        List<String> result = new ArrayList<>();
        for (RecallService service : recallServices) {
            Object detail = resilience.get(service.source());
            if (detail instanceof Map<?, ?> values && "FALLBACK".equals(values.get("status"))) {
                result.add(service.source());
            }
        }
        return result;
    }

    private void cancelAll(Iterable<Future<RecallResult>> futures) {
        for (Future<RecallResult> future : futures) {
            future.cancel(true);
        }
    }

    private void validateUniqueSources(List<RecallService> services) {
        boolean hasInvalidSource = services.stream()
                .map(RecallService::source)
                .anyMatch(source -> source == null || source.isBlank());
        if (hasInvalidSource) {
            throw new IllegalArgumentException("recall service source must not be blank");
        }
        long uniqueSources = services.stream().map(RecallService::source).distinct().count();
        if (uniqueSources != services.size()) {
            throw new IllegalArgumentException("recall service sources must be unique");
        }
    }

    private ThreadFactory daemonThreadFactory() {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "recall-fanout-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public record FanoutResult(List<Item> items, Map<String, Object> debug) {
        public FanoutResult {
            items = List.copyOf(items);
            debug = Collections.unmodifiableMap(new LinkedHashMap<>(debug));
        }
    }

    private record RecallResult(String source, List<Item> items, long costMs) {
        private RecallResult {
            items = List.copyOf(items);
        }
    }
}
