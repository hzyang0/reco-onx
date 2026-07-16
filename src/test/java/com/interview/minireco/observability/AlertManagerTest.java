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
}
