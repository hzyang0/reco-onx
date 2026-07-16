package com.interview.minireco.migration;

import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.RecommendResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RecommendationDiffEngine {
    private static final double SCORE_TOLERANCE = 0.000_001;

    public RecommendationDiff compare(long userId, RecommendResponse legacy, RecommendResponse current) {
        List<Long> legacyIds = itemIds(legacy);
        List<Long> newIds = itemIds(current);
        int overlapCount = overlapCount(legacyIds, newIds);
        int denominator = Math.max(legacyIds.size(), newIds.size());
        double overlapRate = denominator == 0 ? 1.0 : round(overlapCount * 1.0 / denominator);
        int firstMismatchIndex = firstMismatchIndex(legacyIds, newIds);
        int scoreMismatchCount = scoreMismatchCount(legacy.getItems(), current.getItems());
        int legacyRecallCount = debugInt(legacy, "recallItemCount");
        int newRecallCount = debugInt(current, "recallItemCount");

        List<String> reasons = new ArrayList<>();
        if (!legacyIds.equals(newIds)) {
            reasons.add("item_order_or_content");
        }
        if (scoreMismatchCount > 0) {
            reasons.add("score");
        }
        if (legacyRecallCount != newRecallCount) {
            reasons.add("recall_count");
        }
        boolean exactMatch = reasons.isEmpty();

        return new RecommendationDiff(
                userId,
                exactMatch,
                overlapRate,
                overlapCount,
                firstMismatchIndex,
                scoreMismatchCount,
                legacyRecallCount,
                newRecallCount,
                legacy.getCostMs(),
                current.getCostMs(),
                legacyIds,
                newIds,
                reasons,
                Instant.now()
        );
    }

    private List<Long> itemIds(RecommendResponse response) {
        return response.getItems().stream().map(Item::getItemId).toList();
    }

    private int overlapCount(List<Long> left, List<Long> right) {
        Set<Long> rightIds = new HashSet<>(right);
        return (int) left.stream().filter(rightIds::contains).distinct().count();
    }

    private int firstMismatchIndex(List<Long> left, List<Long> right) {
        int sharedLength = Math.min(left.size(), right.size());
        for (int i = 0; i < sharedLength; i++) {
            if (!left.get(i).equals(right.get(i))) {
                return i;
            }
        }
        return left.size() == right.size() ? -1 : sharedLength;
    }

    private int scoreMismatchCount(List<Item> left, List<Item> right) {
        int sharedLength = Math.min(left.size(), right.size());
        int mismatches = 0;
        for (int i = 0; i < sharedLength; i++) {
            if (left.get(i).getItemId() == right.get(i).getItemId()
                    && Math.abs(left.get(i).getScore() - right.get(i).getScore()) > SCORE_TOLERANCE) {
                mismatches++;
            }
        }
        return mismatches;
    }

    private int debugInt(RecommendResponse response, String key) {
        Object value = response.getDebug().get(key);
        return value instanceof Number number ? number.intValue() : -1;
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
