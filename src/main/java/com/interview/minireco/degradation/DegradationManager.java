package com.interview.minireco.degradation;

import com.interview.minireco.service.context.RecommendContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class DegradationManager {
    private static final DegradationManager GLOBAL = new DegradationManager(
            DegradationLevel.parse(System.getenv("DEGRADE_LEVEL"))
    );

    private final AtomicReference<DegradationLevel> level;

    public DegradationManager(DegradationLevel initialLevel) {
        this.level = new AtomicReference<>(initialLevel);
    }

    public static DegradationManager global() {
        return GLOBAL;
    }

    public DegradationLevel getLevel() {
        return level.get();
    }

    public DegradationLevel setLevel(DegradationLevel newLevel) {
        level.set(newLevel);
        return newLevel;
    }

    public DegradationDecision decide(RecommendContext context) {
        DegradationLevel currentLevel = level.get();
        int userBucket = UserLayer.bucket(context.getUserId());
        int originalLimit = context.getLimit();

        if (currentLevel == DegradationLevel.LIGHT && userBucket >= 80) {
            return new DegradationDecision(
                    currentLevel,
                    userBucket,
                    true,
                    originalLimit,
                    Math.min(originalLimit, 8),
                    List.of("ad")
            );
        }

        if (currentLevel == DegradationLevel.HEAVY && userBucket >= 50) {
            return new DegradationDecision(
                    currentLevel,
                    userBucket,
                    true,
                    originalLimit,
                    Math.min(originalLimit, 6),
                    List.of("ad", "live")
            );
        }

        return new DegradationDecision(
                currentLevel,
                userBucket,
                false,
                originalLimit,
                originalLimit,
                List.of()
        );
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("level", level.get().name());
        data.put("rules", List.of(
                Map.of(
                        "level", DegradationLevel.LIGHT.name(),
                        "affectedUserBucket", "80-99",
                        "effectiveLimitMax", 8,
                        "skippedRecallSources", List.of("ad")
                ),
                Map.of(
                        "level", DegradationLevel.HEAVY.name(),
                        "affectedUserBucket", "50-99",
                        "effectiveLimitMax", 6,
                        "skippedRecallSources", List.of("ad", "live")
                )
        ));
        return data;
    }
}
