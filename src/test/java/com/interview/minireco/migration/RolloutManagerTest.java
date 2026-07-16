package com.interview.minireco.migration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolloutManagerTest {
    @Test
    void shouldRouteStableUserBucketsAtFivePercentBoundary() {
        RolloutManager manager = new RolloutManager(5, 0);

        assertEquals(PipelineVersion.NEW, manager.decide(100L).primaryVersion());
        assertEquals(0, manager.decide(100L).userBucket());
        assertEquals(PipelineVersion.NEW, manager.decide(104L).primaryVersion());
        assertEquals(PipelineVersion.LEGACY, manager.decide(105L).primaryVersion());
        assertEquals(PipelineVersion.LEGACY, manager.decide(199L).primaryVersion());
        assertFalse(manager.decide(100L).shadowSelected());
    }

    @Test
    void shouldSelectAllShadowTrafficAtOneHundredPercent() {
        RolloutManager manager = new RolloutManager(0, 100);

        assertTrue(manager.decide(123L).shadowSelected());
        assertEquals(PipelineVersion.LEGACY, manager.decide(123L).primaryVersion());
        assertEquals(PipelineVersion.NEW, manager.decide(123L).shadowVersion());
    }

    @Test
    void shouldRejectInvalidPercent() {
        assertThrows(IllegalArgumentException.class, () -> new RolloutManager(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new RolloutManager(0, 101));
    }
}
