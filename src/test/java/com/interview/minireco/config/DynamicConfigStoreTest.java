package com.interview.minireco.config;

import com.interview.minireco.degradation.DegradationLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamicConfigStoreTest {
    @Test
    void shouldUpdateWithOptimisticVersionAndKeepAuditHistory() {
        DynamicConfigStore store = new DynamicConfigStore(
                Clock.fixed(Instant.parse("2026-07-16T10:00:00Z"), ZoneOffset.UTC)
        );

        RuntimeConfigSnapshot updated = store.update(1, 5, 20, DegradationLevel.LIGHT, "release-bot");

        assertEquals(2, updated.version());
        assertEquals(5, store.get().newPipelinePercent());
        assertEquals(2, store.history().size());
        assertEquals("release-bot", store.history().get(0).updatedBy());
    }

    @Test
    void shouldRejectStaleWriterAndInvalidConfig() {
        DynamicConfigStore store = new DynamicConfigStore();

        assertThrows(ConfigVersionConflictException.class,
                () -> store.update(0, 5, 0, DegradationLevel.NONE, "stale-client"));
        assertThrows(IllegalArgumentException.class,
                () -> store.update(1, 101, 0, DegradationLevel.NONE, "release-bot"));
        assertEquals(1, store.get().version());
    }

    @Test
    void shouldNotPublishInMemoryWhenDurableAppendFails() {
        ConfigJournal failing = new ConfigJournal() {
            private int appends;

            public java.util.List<RuntimeConfigSnapshot> load() { return java.util.List.of(); }

            public void append(RuntimeConfigSnapshot snapshot) {
                if (++appends > 1) { throw new ConfigPersistenceException("disk full"); }
            }

            public String description() { return "failing-test"; }
        };
        DynamicConfigStore store = new DynamicConfigStore(Clock.systemUTC(), failing);

        assertThrows(ConfigPersistenceException.class,
                () -> store.update(1, 5, 0, DegradationLevel.NONE, "release-bot"));
        assertEquals(1, store.get().version());
        assertEquals(100, store.get().newPipelinePercent());
    }
}
