package com.interview.minireco.migration;

import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.RecommendResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationDiffEngineTest {
    private final RecommendationDiffEngine diffEngine = new RecommendationDiffEngine();

    @Test
    void shouldTreatSameBusinessResultAsExactDespiteDifferentCostAndRequestId() {
        RecommendResponse legacy = response("legacy-request", 280, List.of(item(1L, 0.9), item(2L, 0.8)), 25);
        RecommendResponse current = response("new-request", 220, List.of(item(1L, 0.9), item(2L, 0.8)), 25);

        RecommendationDiff diff = diffEngine.compare(123L, legacy, current);

        assertTrue(diff.exactMatch());
        assertEquals(1.0, diff.overlapRate());
        assertEquals(-1, diff.firstMismatchIndex());
        assertEquals(60, diff.legacyCostMs() - diff.newCostMs());
        assertTrue(diff.mismatchReasons().isEmpty());
    }

    @Test
    void shouldReportItemOrderAndRecallCountMismatch() {
        RecommendResponse legacy = response("legacy", 280, List.of(item(1L, 0.9), item(2L, 0.8)), 25);
        RecommendResponse current = response("new", 220, List.of(item(2L, 0.8), item(1L, 0.9)), 20);

        RecommendationDiff diff = diffEngine.compare(123L, legacy, current);

        assertFalse(diff.exactMatch());
        assertEquals(1.0, diff.overlapRate());
        assertEquals(0, diff.firstMismatchIndex());
        assertEquals(List.of("item_order_or_content", "recall_count"), diff.mismatchReasons());
    }

    private RecommendResponse response(
            String requestId,
            long costMs,
            List<Item> items,
            int recallCount
    ) {
        return new RecommendResponse(
                requestId,
                123L,
                "mall",
                costMs,
                items,
                Map.of("recallItemCount", recallCount)
        );
    }

    private Item item(long id, double score) {
        return new Item(id, "item-" + id, "goods", "digital", score);
    }
}
