package com.interview.minireco.observability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricsRegistryTest {
    @Test
    void shouldAggregateTimerMetricsByNameAndTags() {
        MetricsRegistry registry = new MetricsRegistry();

        registry.recordTimer("operator.cost", Map.of("operator", "mixRank", "status", "success"), 100);
        registry.recordTimer("operator.cost", Map.of("operator", "mixRank", "status", "success"), 140);

        List<MetricSample> samples = registry.samples();

        assertEquals(1, samples.size());
        assertEquals("operator.cost", samples.get(0).getName());
        assertEquals(MetricSample.Type.TIMER, samples.get(0).getType());
        assertEquals(2, samples.get(0).getCount());
        assertEquals(240, samples.get(0).getTotal());
        assertEquals(120.0, samples.get(0).getAvg());
        assertEquals(140, samples.get(0).getMax());
        assertEquals(2, samples.get(0).getBucketCounts()[5]);
        assertEquals(2, samples.get(0).getBucketCounts()[11]);
    }
}
