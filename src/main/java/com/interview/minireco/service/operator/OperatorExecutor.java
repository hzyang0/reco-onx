package com.interview.minireco.service.operator;

import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.observability.StructuredLogger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OperatorExecutor implements ExecutionEngine {
    private static final StructuredLogger LOGGER = StructuredLogger.getLogger(OperatorExecutor.class);

    private final List<Operator> operators;
    private final Map<String, OperatorConfig> configByName;
    private final MetricsRegistry metricsRegistry;

    public OperatorExecutor(List<Operator> operators, List<OperatorConfig> configs) {
        this(operators, configs, MetricsRegistry.global());
    }

    public OperatorExecutor(List<Operator> operators, List<OperatorConfig> configs, MetricsRegistry metricsRegistry) {
        this.operators = List.copyOf(operators);
        this.metricsRegistry = metricsRegistry;
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
                metricsRegistry.increment("operator.skipped", Map.of("operator", operator.name()));
                continue;
            }

            long start = System.nanoTime();
            try {
                operator.execute(context);
                long costMs = toMs(System.nanoTime() - start);
                context.addStageCostMs(operator.name(), costMs);
                metricsRegistry.recordTimer("operator.cost", Map.of("operator", operator.name(), "status", "success"), costMs);
                metricsRegistry.increment("operator.success", Map.of("operator", operator.name()));
                LOGGER.debug(context.getRequestId(), "operator_success", () -> Map.of("operator", operator.name(), "costMs", costMs));
            } catch (RuntimeException e) {
                long costMs = toMs(System.nanoTime() - start);
                context.addStageCostMs(operator.name(), costMs);
                metricsRegistry.recordTimer("operator.cost", Map.of("operator", operator.name(), "status", "error"), costMs);
                metricsRegistry.increment("operator.error", Map.of("operator", operator.name()));
                LOGGER.error(context.getRequestId(), "operator_error", () -> Map.of("operator", operator.name(), "costMs", costMs), e);
                throw e;
            }
        }
    }

    private long toMs(long nanos) {
        return nanos / 1_000_000;
    }
}
