package com.interview.minireco.observability;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusMetricsFormatterTest {
    @Test
    void shouldFormatCountersAndTimerHistograms() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.increment("request.success", Map.of("scene", "mall"));
        registry.recordTimer(
                "request.cost",
                Map.of("scene", "mall", "status", "success"),
                120
        );
        registry.recordTimer(
                "request.cost",
                Map.of("scene", "mall", "status", "success"),
                280
        );

        String output = PrometheusMetricsFormatter.format(registry);

        assertTrue(output.contains("# TYPE mini_reco_request_success_total counter"));
        assertTrue(output.contains("mini_reco_request_success_total{scene=\"mall\"} 1"));
        assertTrue(output.contains("# TYPE mini_reco_request_cost_seconds histogram"));
        assertTrue(output.contains(
                "mini_reco_request_cost_seconds_bucket{le=\"0.2\",scene=\"mall\",status=\"success\"} 1"
        ));
        assertTrue(output.contains(
                "mini_reco_request_cost_seconds_bucket{le=\"0.5\",scene=\"mall\",status=\"success\"} 2"
        ));
        assertTrue(output.contains(
                "mini_reco_request_cost_seconds_count{scene=\"mall\",status=\"success\"} 2"
        ));
        assertTrue(output.contains(
                "mini_reco_request_cost_seconds_sum{scene=\"mall\",status=\"success\"} 0.4"
        ));
        assertTrue(output.contains(
                "mini_reco_request_cost_seconds_max{scene=\"mall\",status=\"success\"} 0.28"
        ));
    }

    @Test
    void shouldEscapePrometheusLabelValues() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.increment("custom.event", Map.of("reason", "quote\"\\line\nnext"));

        String output = PrometheusMetricsFormatter.format(registry);

        assertTrue(output.contains("reason=\"quote\\\"\\\\line\\nnext\""));
    }
}
