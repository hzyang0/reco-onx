package com.interview.minireco.service.operator.impl;

import com.interview.minireco.degradation.DegradationDecision;
import com.interview.minireco.degradation.DegradationManager;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.operator.Operator;

import java.util.Map;

public class DegradationOperator implements Operator {
    public static final String NAME = "degradation";

    private final DegradationManager degradationManager;

    public DegradationOperator(DegradationManager degradationManager) {
        this.degradationManager = degradationManager;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(RecommendContext context) {
        DegradationDecision decision = degradationManager.decide(context);
        context.setDegradationDecision(decision);
        context.putDebug("degradation", decision.toMap());
        MetricsRegistry.global().increment(
                "degradation.request",
                Map.of(
                        "level", decision.getLevel().name(),
                        "degraded", String.valueOf(decision.isDegraded())
                )
        );
    }
}
