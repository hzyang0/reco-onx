package com.interview.minireco.resilience;

public enum FaultMode {
    NONE,
    TIMEOUT,
    ERROR;

    public static FaultMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        return FaultMode.valueOf(raw.trim().toUpperCase());
    }
}
