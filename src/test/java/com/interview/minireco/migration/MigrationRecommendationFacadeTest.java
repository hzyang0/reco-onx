package com.interview.minireco.migration;

import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.domain.RecommendResponse;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.service.RecommendationFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MigrationRecommendationFacadeTest {
    @Mock
    private RecommendationFacade legacyPipeline;

    @Mock
    private RecommendationFacade newPipeline;

    @Test
    void shouldRoutePrimaryRequestByUserBucket() {
        RecommendRequest request = new RecommendRequest(100L, "mall", 10);
        RecommendResponse newResponse = response("new", 100L, 220);
        when(newPipeline.recommend(request)).thenReturn(newResponse);
        MigrationRecommendationFacade facade = facade(new RolloutManager(5, 0), new ComparisonRegistry(10));

        RecommendResponse result = facade.recommend(request);

        assertEquals("new", result.getRequestId());
        @SuppressWarnings("unchecked")
        Map<String, Object> migration = (Map<String, Object>) result.getDebug().get("migration");
        assertEquals("NEW", migration.get("primaryPipeline"));
        assertEquals(0, migration.get("userBucket"));
        assertFalse((Boolean) migration.get("shadowSelected"));
        verify(newPipeline).recommend(request);
        verify(legacyPipeline, never()).recommend(request);
    }

    @Test
    void shouldKeepLegacyResponseAndRecordAsynchronousShadowDiff() {
        RecommendRequest request = new RecommendRequest(123L, "mall", 10);
        RecommendResponse legacyResponse = response("legacy", 123L, 280);
        RecommendResponse newResponse = response("new", 123L, 220);
        when(legacyPipeline.recommend(request)).thenReturn(legacyResponse);
        when(newPipeline.recommend(request)).thenReturn(newResponse);
        ComparisonRegistry registry = new ComparisonRegistry(10);
        MigrationRecommendationFacade facade = facade(new RolloutManager(0, 100), registry);

        RecommendResponse result = facade.recommend(request);

        assertEquals("legacy", result.getRequestId());
        @SuppressWarnings("unchecked")
        Map<String, Object> migration = (Map<String, Object>) result.getDebug().get("migration");
        assertEquals("LEGACY", migration.get("primaryPipeline"));
        assertEquals("NEW", migration.get("shadowPipeline"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) registry.snapshot().get("summary");
        assertEquals(1L, summary.get("totalComparisons"));
        assertEquals(1L, summary.get("exactMatches"));
        assertTrue((Double) summary.get("averageCostSavingMs") > 0);
        verify(legacyPipeline).recommend(request);
        verify(newPipeline).recommend(request);
    }

    @Test
    void shouldReturnPrimaryBeforeCapturedShadowTaskRuns() {
        RecommendRequest request = new RecommendRequest(123L, "mall", 10);
        RecommendResponse legacyResponse = response("legacy", 123L, 280);
        RecommendResponse newResponse = response("new", 123L, 220);
        when(legacyPipeline.recommend(request)).thenReturn(legacyResponse);
        when(newPipeline.recommend(request)).thenReturn(newResponse);
        ComparisonRegistry registry = new ComparisonRegistry(10);
        AtomicReference<Runnable> capturedTask = new AtomicReference<>();
        Executor capturingExecutor = capturedTask::set;
        MigrationRecommendationFacade facade = new MigrationRecommendationFacade(
                legacyPipeline,
                newPipeline,
                new RolloutManager(0, 100),
                new RecommendationDiffEngine(),
                registry,
                new MetricsRegistry(),
                capturingExecutor
        );

        RecommendResponse result = facade.recommend(request);

        assertEquals("legacy", result.getRequestId());
        verify(legacyPipeline).recommend(request);
        verify(newPipeline, never()).recommend(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> beforeSummary = (Map<String, Object>) registry.snapshot().get("summary");
        assertEquals(0L, beforeSummary.get("totalComparisons"));

        capturedTask.get().run();

        verify(newPipeline).recommend(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> afterSummary = (Map<String, Object>) registry.snapshot().get("summary");
        assertEquals(1L, afterSummary.get("totalComparisons"));
    }

    private MigrationRecommendationFacade facade(RolloutManager manager, ComparisonRegistry registry) {
        return new MigrationRecommendationFacade(
                legacyPipeline,
                newPipeline,
                manager,
                new RecommendationDiffEngine(),
                registry,
                new MetricsRegistry(),
                Runnable::run
        );
    }

    private RecommendResponse response(String requestId, long userId, long costMs) {
        Item item = new Item(1L, "item", "goods", "digital", 0.9);
        return new RecommendResponse(
                requestId,
                userId,
                "mall",
                costMs,
                List.of(item),
                Map.of("recallItemCount", 25)
        );
    }
}
