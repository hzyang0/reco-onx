package com.interview.minireco.config;

import java.util.List;

public class InMemoryConfigJournal implements ConfigJournal {
    @Override
    public List<RuntimeConfigSnapshot> load() {
        return List.of();
    }

    @Override
    public void append(RuntimeConfigSnapshot snapshot) {
        // The snapshot remains in DynamicConfigStore only; intended for unit tests and local no-path mode.
    }

    @Override
    public String description() {
        return "memory";
    }
}
