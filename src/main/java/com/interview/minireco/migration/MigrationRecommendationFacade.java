package com.interview.minireco.migration;

import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.domain.RecommendResponse;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.observability.StructuredLogger;
import com.interview.minireco.service.RecommendationFacade;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MigrationRecommendationFacade implements RecommendationFacade {
    private static final StructuredLogger LOGGER = StructuredLogger.getLogger(MigrationRecommendationFacade.class);

    private final RecommendationFacade primaryLegacyPipeline;
    private final RecommendationFacade primaryNewPipeline;
    private final RecommendationFacade shadowLegacyPipeline;
    private final RecommendationFacade shadowNewPipeline;
    private final RolloutManager rolloutManager;
    private final RecommendationDiffEngine diffEngine;
    private final ComparisonRegistry comparisonRegistry;
    private final MetricsRegistry metricsRegistry;
    private final Executor shadowExecutor;

    public MigrationRecommendationFacade(
            RecommendationFacade legacyPipeline,
            RecommendationFacade newPipeline,
            RolloutManager rolloutManager,
            RecommendationDiffEngine diffEngine,
            ComparisonRegistry comparisonRegistry
    ) {
        this(
                legacyPipeline,
                newPipeline,
                legacyPipeline,
                newPipeline,
                rolloutManager,
                diffEngine,
                comparisonRegistry,
                MetricsRegistry.global(),
                defaultShadowExecutor()
        );
    }

    public MigrationRecommendationFacade(
            RecommendationFacade primaryLegacyPipeline,
            RecommendationFacade primaryNewPipeline,
            RecommendationFacade shadowLegacyPipeline,
            RecommendationFacade shadowNewPipeline,
            RolloutManager rolloutManager,
            RecommendationDiffEngine diffEngine,
            ComparisonRegistry comparisonRegistry
    ) {
        this(
                primaryLegacyPipeline,
                primaryNewPipeline,
                shadowLegacyPipeline,
                shadowNewPipeline,
                rolloutManager,
                diffEngine,
                comparisonRegistry,
                MetricsRegistry.global(),
                defaultShadowExecutor()
        );
    }

    public MigrationRecommendationFacade(
            RecommendationFacade legacyPipeline,
            RecommendationFacade newPipeline,
            RolloutManager rolloutManager,
            RecommendationDiffEngine diffEngine,
            ComparisonRegistry comparisonRegistry,
            MetricsRegistry metricsRegistry,
            Executor shadowExecutor
    ) {
        this(
                legacyPipeline,
                newPipeline,
                legacyPipeline,
                newPipeline,
                rolloutManager,
                diffEngine,
                comparisonRegistry,
                metricsRegistry,
                shadowExecutor
        );
    }

    public MigrationRecommendationFacade(
            RecommendationFacade primaryLegacyPipeline,
            RecommendationFacade primaryNewPipeline,
            RecommendationFacade shadowLegacyPipeline,
            RecommendationFacade shadowNewPipeline,
            RolloutManager rolloutManager,
            RecommendationDiffEngine diffEngine,
            ComparisonRegistry comparisonRegistry,
            MetricsRegistry metricsRegistry,
            Executor shadowExecutor
    ) {
        this.primaryLegacyPipeline = primaryLegacyPipeline;
        this.primaryNewPipeline = primaryNewPipeline;
        this.shadowLegacyPipeline = shadowLegacyPipeline;
        this.shadowNewPipeline = shadowNewPipeline;
        this.rolloutManager = rolloutManager;
        this.diffEngine = diffEngine;
        this.comparisonRegistry = comparisonRegistry;
        this.metricsRegistry = metricsRegistry;
        this.shadowExecutor = shadowExecutor;
    }

    @Override
    public RecommendResponse recommend(RecommendRequest request) {
        RolloutDecision decision = rolloutManager.decide(request.getUserId());
        RecommendationFacade primaryPipeline = primaryPipeline(decision.primaryVersion());
        RecommendResponse primaryResponse = primaryPipeline.recommend(request);
        metricsRegistry.increment(
                "migration.primary.request",
                Map.of("pipeline", decision.primaryVersion().name().toLowerCase())
        );

        if (decision.shadowSelected()) {
            submitShadow(request, primaryResponse, decision);
        }
        return addMigrationDebug(primaryResponse, decision);
    }

    private void submitShadow(
            RecommendRequest request,
            RecommendResponse primaryResponse,
            RolloutDecision decision
    ) {
        try {
            shadowExecutor.execute(() -> runShadow(request, primaryResponse, decision));
            metricsRegistry.increment(
                    "migration.shadow.submitted",
                    Map.of("pipeline", decision.shadowVersion().name().toLowerCase())
            );
        } catch (RejectedExecutionException e) {
            metricsRegistry.increment("migration.shadow.skipped", Map.of("reason", "queue_full"));
            comparisonRegistry.recordError(request.getUserId(), decision.shadowVersion(), e);
        }
    }

    private void runShadow(
            RecommendRequest request,
            RecommendResponse primaryResponse,
            RolloutDecision decision
    ) {
        try {
            RecommendResponse shadowResponse = shadowPipeline(decision.shadowVersion()).recommend(request);
            RecommendationDiff diff = decision.primaryVersion() == PipelineVersion.LEGACY
                    ? diffEngine.compare(request.getUserId(), primaryResponse, shadowResponse)
                    : diffEngine.compare(request.getUserId(), shadowResponse, primaryResponse);
            comparisonRegistry.record(diff);
            metricsRegistry.increment(
                    "migration.diff",
                    Map.of("exact", String.valueOf(diff.exactMatch()))
            );
        } catch (RuntimeException e) {
            comparisonRegistry.recordError(request.getUserId(), decision.shadowVersion(), e);
            metricsRegistry.increment(
                    "migration.shadow.error",
                    Map.of("pipeline", decision.shadowVersion().name().toLowerCase())
            );
            LOGGER.warn(primaryResponse.getRequestId(), "shadow_pipeline_error", () -> Map.of(
                    "userId", request.getUserId(),
                    "shadowPipeline", decision.shadowVersion().name(),
                    "errorType", e.getClass().getSimpleName()
            ));
        }
    }

    private RecommendationFacade primaryPipeline(PipelineVersion version) {
        return version == PipelineVersion.LEGACY ? primaryLegacyPipeline : primaryNewPipeline;
    }

    private RecommendationFacade shadowPipeline(PipelineVersion version) {
        return version == PipelineVersion.LEGACY ? shadowLegacyPipeline : shadowNewPipeline;
    }

    private RecommendResponse addMigrationDebug(RecommendResponse response, RolloutDecision decision) {
        Map<String, Object> debug = new LinkedHashMap<>(response.getDebug());
        Map<String, Object> migration = new LinkedHashMap<>();
        migration.put("primaryPipeline", decision.primaryVersion().name());
        migration.put("userBucket", decision.userBucket());
        migration.put("newPipelinePercent", decision.newPipelinePercent());
        migration.put("shadowPercent", decision.shadowPercent());
        migration.put("shadowSelected", decision.shadowSelected());
        migration.put("shadowPipeline", decision.shadowSelected() ? decision.shadowVersion().name() : "NONE");
        migration.put("shadowBucket", decision.shadowBucket());
        migration.put("note", "shadow result is asynchronous and is available from /rollout");
        debug.put("migration", migration);
        return new RecommendResponse(
                response.getRequestId(),
                response.getUserId(),
                response.getScene(),
                response.getCostMs(),
                response.getItems(),
                debug
        );
    }

    private static Executor defaultShadowExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(50),
                daemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "migration-shadow-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
