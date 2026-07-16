package com.interview.minireco.degradation;

import java.util.Locale;

public enum DegradationLevel {
    NONE,
    LIGHT,
    HEAVY;

    public static DegradationLevel parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        return DegradationLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
