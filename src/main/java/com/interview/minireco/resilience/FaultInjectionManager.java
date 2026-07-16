package com.interview.minireco.resilience;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class FaultInjectionManager {
    private static final FaultInjectionManager GLOBAL = new FaultInjectionManager();
    private static final List<String> SUPPORTED_SOURCES = List.of("goods", "live", "ad");

    private final ConcurrentMap<String, FaultMode> modes = new ConcurrentHashMap<>();

    public static FaultInjectionManager global() {
        return GLOBAL;
    }

    public FaultMode get(String source) {
        return modes.getOrDefault(source, FaultMode.NONE);
    }

    public void set(String source, FaultMode mode) {
        if (!SUPPORTED_SOURCES.contains(source)) {
            throw new IllegalArgumentException("unsupported recall source: " + source);
        }
        if (mode == FaultMode.NONE) {
            modes.remove(source);
        } else {
            modes.put(source, mode);
        }
    }

    public void reset() {
        modes.clear();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String source : SUPPORTED_SOURCES) {
            data.put(source, get(source).name());
        }
        return data;
    }
}
