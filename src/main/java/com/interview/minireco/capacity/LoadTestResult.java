package com.interview.minireco.capacity;

import java.util.LinkedHashMap;
import java.util.Map;

public record LoadTestResult(
        int concurrency,
        long requests,
        long successes,
        long errors,
        long fallbacks,
        double durationSeconds,
        double throughputQps,
        double successRatePercent,
        double fallbackRatePercent,
        long p50Ms,
        long p95Ms,
        long p99Ms,
        long maxMs,
        boolean sloPass
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("concurrency", concurrency);
        map.put("requests", requests);
        map.put("successes", successes);
        map.put("errors", errors);
        map.put("fallbacks", fallbacks);
        map.put("durationSeconds", round(durationSeconds));
        map.put("throughputQps", round(throughputQps));
        map.put("successRatePercent", round(successRatePercent));
        map.put("fallbackRatePercent", round(fallbackRatePercent));
        map.put("p50Ms", p50Ms);
        map.put("p95Ms", p95Ms);
        map.put("p99Ms", p99Ms);
        map.put("maxMs", maxMs);
        map.put("sloPass", sloPass);
        return map;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
