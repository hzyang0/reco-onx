package com.interview.minireco.resilience;

import com.interview.minireco.service.downstream.RecallService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ResilienceRegistry {
    private static final ResilienceRegistry GLOBAL = new ResilienceRegistry();

    private final ConcurrentMap<String, ResilientRecallService> services = new ConcurrentHashMap<>();

    public static ResilienceRegistry global() {
        return GLOBAL;
    }

    public RecallService register(ResilientRecallService service) {
        services.put(service.source(), service);
        return service;
    }

    public void resetAll() {
        services.values().forEach(ResilientRecallService::resetCircuit);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> serviceSnapshots = new LinkedHashMap<>();
        services.keySet().stream().sorted().forEach(source ->
                serviceSnapshots.put(source, services.get(source).snapshot())
        );
        return Map.of("services", serviceSnapshots);
    }
}
