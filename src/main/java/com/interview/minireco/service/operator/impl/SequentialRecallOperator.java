package com.interview.minireco.service.operator.impl;

import com.interview.minireco.degradation.DegradationDecision;
import com.interview.minireco.domain.Item;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.RecallService;
import com.interview.minireco.service.operator.Operator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Legacy baseline retained for shadow comparison during the V11 migration. */
public class SequentialRecallOperator implements Operator {
    private final List<RecallService> recallServices;
    private final MetricsRegistry metricsRegistry;

    public SequentialRecallOperator(List<RecallService> recallServices) {
        this(recallServices, MetricsRegistry.global());
    }

    public SequentialRecallOperator(List<RecallService> recallServices, MetricsRegistry metricsRegistry) {
        this.recallServices = List.copyOf(recallServices);
        this.metricsRegistry = metricsRegistry;
    }

    @Override
    public String name() {
        return RecallOperator.NAME;
    }

    @Override
    public void execute(RecommendContext context) {
        long start = System.nanoTime();
        DegradationDecision decision = context.getDegradationDecision();
        List<Item> items = new ArrayList<>();
        List<String> completedSources = new ArrayList<>();
        List<String> skippedSources = new ArrayList<>();
        Map<String, String> failedSources = new LinkedHashMap<>();
        Map<String, Long> sourceCostMs = new LinkedHashMap<>();
        Map<String, Integer> itemCountBySource = new LinkedHashMap<>();

        for (RecallService recallService : recallServices) {
            String source = recallService.source();
            if (decision.shouldSkipRecallSource(source)) {
                skippedSources.add(source);
                metricsRegistry.increment(
                        "degradation.recall.skipped",
                        Map.of("level", decision.getLevel().name(), "source", source)
                );
                continue;
            }

            long sourceStart = System.nanoTime();
            try {
                List<Item> sourceItems = recallService.recall(context);
                items.addAll(sourceItems);
                completedSources.add(source);
                itemCountBySource.put(source, sourceItems.size());
            } catch (RuntimeException e) {
                failedSources.put(source, e.getClass().getSimpleName());
            } finally {
                sourceCostMs.put(source, elapsedMs(sourceStart));
            }
        }

        List<String> fallbackSources = resilienceFallbackSources(context);
        boolean partial = !failedSources.isEmpty() || !fallbackSources.isEmpty();
        long totalCostMs = elapsedMs(start);
        metricsRegistry.recordTimer(
                "recall.legacy.cost",
                Map.of("status", partial ? "partial" : "success"),
                totalCostMs
        );

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("mode", "SEQUENTIAL");
        debug.put("status", partial ? "PARTIAL" : "SUCCESS");
        debug.put("costMs", totalCostMs);
        debug.put("completedSources", completedSources);
        debug.put("degradationSkippedSources", skippedSources);
        debug.put("fallbackSources", fallbackSources);
        debug.put("failedSources", failedSources);
        debug.put("sourceCostMs", sourceCostMs);
        debug.put("itemCountBySource", itemCountBySource);

        context.setRecalledItems(items);
        context.putDebug("recallItemCount", items.size());
        context.putDebug("recallExecution", debug);
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

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
