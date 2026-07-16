package com.interview.minireco.observability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlertManagerTest {
    @Test
    void shouldCreateAlertWhenOperatorCostExceedsThreshold() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.recordTimer("operator.cost", Map.of("operator", "onlineFeature", "status", "success"), 180);

        AlertManager alertManager = new AlertManager(registry);

        @SuppressWarnings("unchecked")
        List<Object> alerts = (List<Object>) alertManager.snapshot().get("alerts");

        assertEquals(1, alerts.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> alert = (Map<String, Object>) alerts.get(0);
        assertEquals("operator_cost_too_high", alert.get("rule"));
    }

    @Test
    void shouldCreateAlertWhenDownstreamUsesFallback() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.increment("downstream.fallback", Map.of("source", "live", "reason", "timeout"));

        AlertManager alertManager = new AlertManager(registry);

        @SuppressWarnings("unchecked")
        List<Object> alerts = (List<Object>) alertManager.snapshot().get("alerts");
        @SuppressWarnings("unchecked")
        Map<String, Object> alert = (Map<String, Object>) alerts.get(0);

        assertEquals(1, alerts.size());
        assertEquals("downstream_fallback_happened", alert.get("rule"));
    }

    @Test
    void shouldCreateAlertWhenRecallFanoutReachesDeadline() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.increment("recall.fanout.timeout", Map.of("source", "live"));

        AlertManager alertManager = new AlertManager(registry);

        @SuppressWarnings("unchecked")
        List<Object> alerts = (List<Object>) alertManager.snapshot().get("alerts");
        @SuppressWarnings("unchecked")
        Map<String, Object> alert = (Map<String, Object>) alerts.get(0);

        assertEquals(1, alerts.size());
        assertEquals("recall_fanout_timeout", alert.get("rule"));
    }
}
