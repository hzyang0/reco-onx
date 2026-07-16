package com.interview.minireco.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DegradationBenchmarkTest {
    @Test
    void shouldCalculateNearestRankPercentile() {
        List<Long> costs = List.of(50L, 10L, 40L, 20L, 30L);

        assertEquals(30L, DegradationBenchmark.percentile(costs, 0.50));
        assertEquals(50L, DegradationBenchmark.percentile(costs, 0.95));
    }

    @Test
    void shouldValidateIterationArgument() {
        assertEquals(5, DegradationBenchmark.parseIterations(new String[0]));
        assertEquals(3, DegradationBenchmark.parseIterations(new String[]{"3"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> DegradationBenchmark.parseIterations(new String[]{"0"})
        );
    }
}
