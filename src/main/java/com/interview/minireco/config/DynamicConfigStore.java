package com.interview.minireco.config;

import com.interview.minireco.degradation.DegradationLevel;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class DynamicConfigStore {
    private static final int MAX_HISTORY = 20;

    private final AtomicReference<RuntimeConfigSnapshot> current;
    private final Deque<RuntimeConfigSnapshot> history = new ArrayDeque<>();
    private final Clock clock;

    public DynamicConfigStore() {
        this(Clock.systemUTC());
    }

    DynamicConfigStore(Clock clock) {
        this.clock = clock;
        RuntimeConfigSnapshot initial = new RuntimeConfigSnapshot(
                1, 100, 0, DegradationLevel.NONE, "bootstrap", Instant.now(clock)
        );
        this.current = new AtomicReference<>(initial);
        history.addFirst(initial);
    }

    public RuntimeConfigSnapshot get() {
        return current.get();
    }

    public synchronized RuntimeConfigSnapshot update(
            long expectedVersion,
            int newPipelinePercent,
            int shadowPercent,
            DegradationLevel degradationLevel,
            String updatedBy
    ) {
        RuntimeConfigSnapshot before = current.get();
        if (before.version() != expectedVersion) {
            throw new ConfigVersionConflictException(expectedVersion, before.version());
        }
        RuntimeConfigSnapshot next = new RuntimeConfigSnapshot(
                before.version() + 1,
                newPipelinePercent,
                shadowPercent,
                degradationLevel,
                updatedBy,
                Instant.now(clock)
        );
        current.set(next);
        history.addFirst(next);
        while (history.size() > MAX_HISTORY) {
            history.removeLast();
        }
        return next;
    }

    public synchronized List<RuntimeConfigSnapshot> history() {
        return new ArrayList<>(history);
    }
}
