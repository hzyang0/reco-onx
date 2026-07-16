package com.interview.minireco.service.operator.impl;

import com.interview.minireco.degradation.DegradationDecision;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.RecallService;
import com.interview.minireco.service.operator.Operator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RecallOperator implements Operator {
    public static final String NAME = "recall";

    private final List<RecallService> recallServices;
    private final ParallelRecallFanout parallelRecallFanout;
    private final MetricsRegistry metricsRegistry;

    public RecallOperator(List<RecallService> recallServices) {
        this(recallServices, RecallFanoutConfig.defaults(), MetricsRegistry.global());
    }

    public RecallOperator(
            List<RecallService> recallServices,
            RecallFanoutConfig config,
            MetricsRegistry metricsRegistry
    ) {
        this.recallServices = List.copyOf(recallServices);
        this.metricsRegistry = metricsRegistry;
        this.parallelRecallFanout = new ParallelRecallFanout(this.recallServices, config, metricsRegistry);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(RecommendContext context) {
        DegradationDecision decision = context.getDegradationDecision();
        List<String> skippedSources = new ArrayList<>();
        for (RecallService recallService : recallServices) {
            if (!decision.shouldSkipRecallSource(recallService.source())) {
                continue;
            }
            skippedSources.add(recallService.source());
            metricsRegistry.increment(
                    "degradation.recall.skipped",
                    Map.of(
                            "level", decision.getLevel().name(),
                            "source", recallService.source()
                    )
            );
        }

        ParallelRecallFanout.FanoutResult result = parallelRecallFanout.execute(context, skippedSources);
        context.setRecalledItems(result.items());
        context.putDebug("recallItemCount", result.items().size());
        context.putDebug("recallFanout", result.debug());
    }
}
