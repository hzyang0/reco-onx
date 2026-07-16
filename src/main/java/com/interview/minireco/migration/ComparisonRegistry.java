package com.interview.minireco.migration;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ComparisonRegistry {
    private static final int DEFAULT_RECENT_LIMIT = 50;
    private static final ComparisonRegistry GLOBAL = new ComparisonRegistry(DEFAULT_RECENT_LIMIT);

    private final int recentLimit;
    private final Deque<Map<String, Object>> recent = new ArrayDeque<>();
    private long totalComparisons;
    private long exactMatches;
    private long mismatches;
    private long shadowErrors;
    private double overlapRateSum;
    private long legacyCostTotalMs;
    private long newCostTotalMs;

    public ComparisonRegistry(int recentLimit) {
        if (recentLimit <= 0) {
            throw new IllegalArgumentException("recentLimit must be positive");
        }
        this.recentLimit = recentLimit;
    }

    public static ComparisonRegistry global() {
        return GLOBAL;
    }

    public synchronized void record(RecommendationDiff diff) {
        totalComparisons++;
        if (diff.exactMatch()) {
            exactMatches++;
        } else {
            mismatches++;
        }
        overlapRateSum += diff.overlapRate();
        legacyCostTotalMs += diff.legacyCostMs();
        newCostTotalMs += diff.newCostMs();
        addRecent(diff.toMap());
    }

    public synchronized void recordError(long userId, PipelineVersion shadowVersion, Throwable error) {
        shadowErrors++;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "SHADOW_ERROR");
        data.put("userId", userId);
        data.put("shadowVersion", shadowVersion.name());
        data.put("errorType", error.getClass().getSimpleName());
        data.put("errorMessage", String.valueOf(error.getMessage()));
        data.put("time", Instant.now().toString());
        addRecent(data);
    }

    public synchronized void reset() {
        recent.clear();
        totalComparisons = 0;
        exactMatches = 0;
        mismatches = 0;
        shadowErrors = 0;
        overlapRateSum = 0;
        legacyCostTotalMs = 0;
        newCostTotalMs = 0;
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalComparisons", totalComparisons);
        summary.put("exactMatches", exactMatches);
        summary.put("mismatches", mismatches);
        summary.put("shadowErrors", shadowErrors);
        summary.put("exactMatchRate", rate(exactMatches, totalComparisons));
        summary.put("averageOverlapRate", totalComparisons == 0 ? 0.0 : round(overlapRateSum / totalComparisons));
        summary.put("averageLegacyCostMs", average(legacyCostTotalMs, totalComparisons));
        summary.put("averageNewCostMs", average(newCostTotalMs, totalComparisons));
        summary.put("averageCostSavingMs", average(legacyCostTotalMs - newCostTotalMs, totalComparisons));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", summary);
        data.put("recent", new ArrayList<>(recent));
        return data;
    }

    private void addRecent(Map<String, Object> data) {
        recent.addFirst(new LinkedHashMap<>(data));
        while (recent.size() > recentLimit) {
            recent.removeLast();
        }
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : round(numerator * 1.0 / denominator);
    }

    private double average(long total, long count) {
        return count == 0 ? 0.0 : round(total * 1.0 / count);
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
