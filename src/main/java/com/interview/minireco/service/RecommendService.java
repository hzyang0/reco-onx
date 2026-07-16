package com.interview.minireco.service;

import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.domain.RecommendResponse;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.operator.ExecutionEngine;

import java.util.UUID;

public class RecommendService {
    private final ExecutionEngine executionEngine;

    public RecommendService(ExecutionEngine executionEngine) {
        this.executionEngine = executionEngine;
    }

    public RecommendResponse recommend(RecommendRequest request) {
        long totalStart = System.nanoTime();
        RecommendContext context = new RecommendContext(UUID.randomUUID().toString(), request);

        executionEngine.execute(context);

        long totalCostMs = toMs(System.nanoTime() - totalStart);

        System.out.printf(
                "requestId=%s userId=%d scene=%s totalCostMs=%d recall=%d filtered=%d returned=%d%n",
                context.getRequestId(),
                context.getUserId(),
                context.getScene(),
                totalCostMs,
                context.getRecalledItems().size(),
                context.getFilteredItems().size(),
                context.getFinalItems().size()
        );

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
}
