package io.github.hzyang0.minireco.service.downstream.impl;

import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.domain.UserFeature;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.downstream.MixRankService;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A deterministic local ranking policy. It is a replaceable boundary for a
 * future model-serving client, rather than a fabricated score generator.
 */
public final class RuleBasedMixRankService implements MixRankService {
    @Override
    public List<Item> rank(List<Item> items, RecommendContext context, int limit) {
        UserFeature feature = context.getUserFeature();
        Map<String, String> abParams = context.getAbParams();
        double categoryBoost = feature.isNewUser() ? 0.08 : 0.15;

        for (Item item : items) {
            double score = item.getScore();
            if (item.getCategory().equals(feature.getPreferredCategory())) {
                score += categoryBoost;
            }
            if ("MALL_BOOST".equals(abParams.get("rank_exp")) && "goods".equals(item.getSource())) {
                score += 0.05;
            }
            if ("ad".equals(item.getSource())) {
                score -= 0.03;
            }
            item.setScore(score);
        }

        context.putDebug("rankingPolicy", Map.of(
                "coldStart", feature.isNewUser(),
                "categoryBoost", categoryBoost,
                "basis", feature.isNewUser()
                        ? "declared_category_plus_popularity"
                        : "profile_and_behavior"
        ));

        return items.stream()
                .sorted(Comparator.comparingDouble(Item::getScore).reversed())
                .limit(limit)
                .toList();
    }
}
