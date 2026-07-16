package com.interview.minireco.migration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RecommendationDiff(
        long userId,
        boolean exactMatch,
        double overlapRate,
        int overlapCount,
        int firstMismatchIndex,
        int scoreMismatchCount,
        int legacyRecallCount,
        int newRecallCount,
        long legacyCostMs,
        long newCostMs,
        List<Long> legacyItemIds,
        List<Long> newItemIds,
        List<String> mismatchReasons,
        Instant comparedAt
) {
    public RecommendationDiff {
        legacyItemIds = List.copyOf(legacyItemIds);
        newItemIds = List.copyOf(newItemIds);
        mismatchReasons = List.copyOf(mismatchReasons);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "DIFF");
        data.put("userId", userId);
        data.put("exactMatch", exactMatch);
        data.put("overlapRate", overlapRate);
        data.put("overlapCount", overlapCount);
        data.put("firstMismatchIndex", firstMismatchIndex);
        data.put("scoreMismatchCount", scoreMismatchCount);
        data.put("legacyRecallCount", legacyRecallCount);
        data.put("newRecallCount", newRecallCount);
        data.put("legacyCostMs", legacyCostMs);
        data.put("newCostMs", newCostMs);
        data.put("costSavingMs", legacyCostMs - newCostMs);
        data.put("legacyItemIds", legacyItemIds);
        data.put("newItemIds", newItemIds);
        data.put("mismatchReasons", mismatchReasons);
        data.put("comparedAt", comparedAt.toString());
        return data;
    }
}
