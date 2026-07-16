package com.interview.minireco.capacity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LoadResultCalculator {
    private LoadResultCalculator() {
    }

    public static LoadTestResult calculate(int concurrency, List<Sample> samples, double durationSeconds,
                                           double minSuccessRate, double maxFallbackRate, long maxP95Ms) {
        if (samples.isEmpty()) {
            return new LoadTestResult(concurrency, 0, 0, 0, 0, durationSeconds,
                    0, 0, 0, 0, 0, 0, 0, false);
        }
        List<Long> latencies = new ArrayList<>(samples.size());
        long successes = 0;
        long fallbacks = 0;
        for (Sample sample : samples) {
            latencies.add(sample.latencyMs());
            if (sample.success()) { successes++; }
            if (sample.fallback()) { fallbacks++; }
        }
        Collections.sort(latencies);
        long requests = samples.size();
        double successRate = successes * 100.0 / requests;
        double fallbackRate = fallbacks * 100.0 / requests;
        long p95 = percentile(latencies, 95);
        boolean pass = successRate >= minSuccessRate && fallbackRate <= maxFallbackRate && p95 <= maxP95Ms;
        return new LoadTestResult(
                concurrency, requests, successes, requests - successes, fallbacks, durationSeconds,
                requests / durationSeconds, successRate, fallbackRate,
                percentile(latencies, 50), p95, percentile(latencies, 99), latencies.get(latencies.size() - 1), pass
        );
    }

    public static long percentile(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) { return 0; }
        int index = Math.max(0, (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1);
        return sortedValues.get(index);
    }

    public record Sample(long latencyMs, boolean success, boolean fallback) {
    }
}
