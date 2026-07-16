package com.interview.minireco.degradation;

import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.service.context.RecommendContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DegradationManagerTest {
    @Test
    void lightLevelShouldOnlyAffectLowPriorityUsers() {
        DegradationManager manager = new DegradationManager(DegradationLevel.LIGHT);

        DegradationDecision lowPriority = manager.decide(context(185L, 10));
        DegradationDecision protectedUser = manager.decide(context(123L, 10));

        assertTrue(lowPriority.isDegraded());
        assertEquals(85, lowPriority.getUserBucket());
        assertEquals(8, lowPriority.getEffectiveLimit());
        assertEquals(List.of("ad"), lowPriority.getSkippedRecallSources());

        assertFalse(protectedUser.isDegraded());
        assertEquals(23, protectedUser.getUserBucket());
        assertEquals(10, protectedUser.getEffectiveLimit());
        assertEquals(List.of(), protectedUser.getSkippedRecallSources());
    }

    @Test
    void heavyLevelShouldSkipAdAndLiveForHalfOfUsers() {
        DegradationManager manager = new DegradationManager(DegradationLevel.HEAVY);

        DegradationDecision decision = manager.decide(context(160L, 10));

        assertTrue(decision.isDegraded());
        assertEquals(60, decision.getUserBucket());
        assertEquals(6, decision.getEffectiveLimit());
        assertEquals(List.of("ad", "live"), decision.getSkippedRecallSources());
    }

    private RecommendContext context(long userId, int limit) {
        return new RecommendContext("request-" + userId, new RecommendRequest(userId, "mall", limit));
    }
}
