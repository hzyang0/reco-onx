package com.interview.minireco.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AlertManager {
    private static final long REQUEST_COST_WARN_MS = 500;
    private static final long OPERATOR_COST_WARN_MS = 150;

    private final MetricsRegistry metricsRegistry;

    public AlertManager(MetricsRegistry metricsRegistry) {
        this.metricsRegistry = metricsRegistry;
    }

    public Map<String, Object> snapshot() {
        List<Object> alerts = new ArrayList<>();
        for (MetricSample sample : metricsRegistry.samples()) {
            if ("request.cost".equals(sample.getName()) && sample.getMax() > REQUEST_COST_WARN_MS) {
                alerts.add(Map.of(
                        "level", "WARN",
                        "rule", "request_cost_too_high",
                        "thresholdMs", REQUEST_COST_WARN_MS,
                        "metric", sample.toMap()
                ));
            }
            if ("operator.cost".equals(sample.getName()) && sample.getMax() > OPERATOR_COST_WARN_MS) {
                alerts.add(Map.of(
                        "level", "WARN",
                        "rule", "operator_cost_too_high",
                        "thresholdMs", OPERATOR_COST_WARN_MS,
                        "metric", sample.toMap()
                ));
            }
            if ("operator.error".equals(sample.getName()) && sample.getCount() > 0) {
                alerts.add(Map.of(
                        "level", "ERROR",
                        "rule", "operator_has_error",
                        "metric", sample.toMap()
                ));
            }
            if ("request.error".equals(sample.getName()) && sample.getCount() > 0) {
                alerts.add(Map.of(
                        "level", "ERROR",
                        "rule", "request_has_error",
                        "metric", sample.toMap()
                ));
            }
        }
        return Map.of("alerts", alerts);
    }
}
