package com.interview.minireco.capacity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadResultCalculatorTest {
    @Test
    void shouldCalculateNearestRankPercentilesAndPassingSlo() {
        List<LoadResultCalculator.Sample> samples = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            samples.add(new LoadResultCalculator.Sample(i, true, false));
        }

        LoadTestResult result = LoadResultCalculator.calculate(4, samples, 10, 99, 1, 100);

        assertEquals(10.0, result.throughputQps());
        assertEquals(50, result.p50Ms());
        assertEquals(95, result.p95Ms());
        assertEquals(99, result.p99Ms());
        assertTrue(result.sloPass());
    }

    @Test
    void shouldFailWhenCorrectnessFallbackOrLatencyBreaksBudget() {
        List<LoadResultCalculator.Sample> samples = List.of(
                new LoadResultCalculator.Sample(10, true, false),
                new LoadResultCalculator.Sample(20, true, true),
                new LoadResultCalculator.Sample(200, false, false)
        );

        LoadTestResult result = LoadResultCalculator.calculate(2, samples, 1, 99, 1, 100);

        assertEquals(1, result.errors());
        assertEquals(1, result.fallbacks());
        assertFalse(result.sloPass());
    }
}
