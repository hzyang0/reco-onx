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
    private final ConfigJournal journal;

    public DynamicConfigStore() {
        this(Clock.systemUTC(), new InMemoryConfigJournal());
    }

    DynamicConfigStore(Clock clock) {
        this(clock, new InMemoryConfigJournal());
    }

    DynamicConfigStore(Clock clock, ConfigJournal journal) {
        this.clock = clock;
        this.journal = journal;
        List<RuntimeConfigSnapshot> persisted = journal.load();
        if (persisted.isEmpty()) {
            RuntimeConfigSnapshot initial = new RuntimeConfigSnapshot(
                    1, 100, 0, DegradationLevel.NONE, "bootstrap", Instant.now(clock)
            );
            journal.append(initial);
            persisted = List.of(initial);
        }
        this.current = new AtomicReference<>(persisted.get(persisted.size() - 1));
        for (RuntimeConfigSnapshot snapshot : persisted) {
            history.addFirst(snapshot);
            trimHistory();
        }
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
        journal.append(next);
        current.set(next);
        history.addFirst(next);
        trimHistory();
        return next;
    }

    public synchronized List<RuntimeConfigSnapshot> history() {
        return new ArrayList<>(history);
    }

    public String storageDescription() {
        return journal.description();
    }

    private void trimHistory() {
        while (history.size() > MAX_HISTORY) {
            history.removeLast();
        }
    }
}
