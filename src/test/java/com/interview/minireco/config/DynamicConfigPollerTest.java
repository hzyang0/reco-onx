package com.interview.minireco.config;

import com.interview.minireco.degradation.DegradationLevel;
import com.interview.minireco.degradation.DegradationManager;
import com.interview.minireco.migration.RolloutManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicConfigPollerTest {
    @Test
    void shouldApplyOnlyNewerVersionToAllRuntimeManagers() throws Exception {
        AtomicReference<RuntimeConfigSnapshot> remote = new AtomicReference<>(snapshot(2, 5, 30, DegradationLevel.LIGHT));
        RolloutManager rollout = new RolloutManager(100, 0);
        DegradationManager degradation = new DegradationManager(DegradationLevel.NONE);
        DynamicConfigPoller poller = new DynamicConfigPoller(remote::get, rollout, degradation, 3000);

        assertTrue(poller.pollOnce());
        assertEquals(5, rollout.getNewPipelinePercent());
        assertEquals(30, rollout.getShadowPercent());
        assertEquals(DegradationLevel.LIGHT, degradation.getLevel());
        assertEquals(2L, poller.snapshot().get("appliedVersion"));

        remote.set(snapshot(1, 100, 0, DegradationLevel.NONE));
        assertEquals(false, poller.pollOnce());
        assertEquals(5, rollout.getNewPipelinePercent());
    }

    @Test
    void shouldRetainLastKnownGoodWhenFetchFails() throws Exception {
        AtomicReference<ConfigFetcher> delegate = new AtomicReference<>(
                () -> snapshot(2, 0, 100, DegradationLevel.HEAVY)
        );
        RolloutManager rollout = new RolloutManager(100, 0);
        DegradationManager degradation = new DegradationManager(DegradationLevel.NONE);
        DynamicConfigPoller poller = new DynamicConfigPoller(
                () -> delegate.get().fetch(), rollout, degradation, 3000
        );
        poller.pollOnce();
        delegate.set(() -> { throw new IOException("config center unavailable"); });

        assertThrows(IOException.class, poller::pollOnce);
        assertEquals(0, rollout.getNewPipelinePercent());
        assertEquals(DegradationLevel.HEAVY, degradation.getLevel());
    }

    private RuntimeConfigSnapshot snapshot(long version, int rollout, int shadow, DegradationLevel level) {
        return new RuntimeConfigSnapshot(version, rollout, shadow, level, "test", Instant.parse("2026-07-16T10:00:00Z"));
    }
}
