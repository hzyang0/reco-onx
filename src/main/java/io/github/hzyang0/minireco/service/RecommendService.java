package io.github.hzyang0.minireco.service;

import io.github.hzyang0.minireco.domain.RecommendRequest;
import io.github.hzyang0.minireco.domain.RecommendResponse;
import io.github.hzyang0.minireco.observability.MetricsRegistry;
import io.github.hzyang0.minireco.observability.StructuredLogger;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.operator.ExecutionEngine;

import java.util.Map;
import java.util.UUID;

public class RecommendService implements RecommendationFacade {
    private static final StructuredLogger LOGGER = StructuredLogger.getLogger(RecommendService.class);

    private final ExecutionEngine executionEngine;
    private final MetricsRegistry metricsRegistry;

    public RecommendService(ExecutionEngine executionEngine) {
        this(executionEngine, MetricsRegistry.global());
    }

    public RecommendService(ExecutionEngine executionEngine, MetricsRegistry metricsRegistry) {
        this.executionEngine = executionEngine;
        this.metricsRegistry = metricsRegistry;
    }

    @Override
    public RecommendResponse recommend(RecommendRequest request) {
        long totalStart = System.nanoTime();
        RecommendContext context = new RecommendContext(UUID.randomUUID().toString(), request);

        try {
            executionEngine.execute(context);
        } catch (RuntimeException e) {
            long errorCostMs = toMs(System.nanoTime() - totalStart);
            metricsRegistry.recordTimer("request.cost", Map.of("scene", request.getScene(), "status", "error"), errorCostMs);
            metricsRegistry.increment("request.error", Map.of("scene", request.getScene()));
            LOGGER.error(context.getRequestId(), "request_error", () -> Map.of(
                    "scene", request.getScene(),
                    "userId", request.getUserId(),
                    "costMs", errorCostMs
            ), e);
            throw e;
        }

        long totalCostMs = toMs(System.nanoTime() - totalStart);
        metricsRegistry.recordTimer("request.cost", Map.of("scene", context.getScene(), "status", "success"), totalCostMs);
        metricsRegistry.increment("request.success", Map.of("scene", context.getScene()));

        LOGGER.info(context.getRequestId(), "request_success", () -> Map.of(
                "userId", context.getUserId(),
                "scene", context.getScene(),
                "costMs", totalCostMs,
                "recallItemCount", context.getRecalledItems().size(),
                "filteredItemCount", context.getFilteredItems().size(),
                "returnedItemCount", context.getFinalItems().size()
        ));

        return new RecommendResponse(
                context.getRequestId(),
                context.getUserId(),
                context.getScene(),
                totalCostMs,
                context.getFinalItems(),
                context.buildDebugSnapshot()
        );
    }

    private long toMs(long nanos) {
        return nanos / 1_000_000;
    }

    @Override
    public void close() {
        executionEngine.close();
    }
}
