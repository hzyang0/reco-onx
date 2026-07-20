package com.interview.minireco.config;

import java.util.List;

public interface ConfigJournal {
    List<RuntimeConfigSnapshot> load();

    void append(RuntimeConfigSnapshot snapshot);

    String description();
}
