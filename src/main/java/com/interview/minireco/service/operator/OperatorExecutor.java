package com.interview.minireco.service.operator;

import com.interview.minireco.service.context.RecommendContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OperatorExecutor implements ExecutionEngine {
    private final List<Operator> operators;
    private final Map<String, OperatorConfig> configByName;

    public OperatorExecutor(List<Operator> operators, List<OperatorConfig> configs) {
        this.operators = List.copyOf(operators);
        this.configByName = new LinkedHashMap<>();
        for (OperatorConfig config : configs) {
            this.configByName.put(config.getName(), config);
        }
    }

    @Override
    public void execute(RecommendContext context) {
        for (Operator operator : operators) {
            OperatorConfig config = configByName.getOrDefault(operator.name(), OperatorConfig.enabled(operator.name()));
            if (!config.isEnabled()) {
                context.addStageCostMs(operator.name(), 0);
                context.putDebug(operator.name() + "Skipped", true);
                continue;
            }

            long start = System.nanoTime();
            operator.execute(context);
            context.addStageCostMs(operator.name(), toMs(System.nanoTime() - start));
        }
    }

    private long toMs(long nanos) {
        return nanos / 1_000_000;
    }
}
