package io.github.hzyang0.minireco.service.operator.impl;

import io.github.hzyang0.minireco.observability.MetricsRegistry;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.downstream.RecallService;
import io.github.hzyang0.minireco.service.operator.Operator;

import java.util.List;

public class RecallOperator implements Operator {
    public static final String NAME = "recall";

    private final ParallelRecallFanout parallelRecallFanout;

    public RecallOperator(List<RecallService> recallServices) {
        this(recallServices, RecallFanoutConfig.defaults(), MetricsRegistry.global());
    }

    public RecallOperator(
            List<RecallService> recallServices,
            RecallFanoutConfig config,
            MetricsRegistry metricsRegistry
    ) {
        this.parallelRecallFanout = new ParallelRecallFanout(recallServices, config, metricsRegistry);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(RecommendContext context) {
        ParallelRecallFanout.FanoutResult result = parallelRecallFanout.execute(context);
        context.setRecalledItems(result.items());
        context.putDebug("recallItemCount", result.items().size());
        context.putDebug("recallFanout", result.debug());
    }

    @Override
    public void close() {
        parallelRecallFanout.close();
    }
}
