package com.interview.minireco.service.downstream.impl;

import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.MixRankService;
import com.interview.minireco.util.SimulatedLatency;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class DemoMixRankService implements MixRankService {
    @Override
    public List<Item> rank(List<Item> items, RecommendContext context, int limit) {
        SimulatedLatency.sleepMs(120);
        UserFeature feature = context.getUserFeature();
        Map<String, String> abParams = context.getAbParams();

        for (Item item : items) {
            double score = item.getScore();
            if (item.getCategory().equals(feature.getPreferredCategory())) {
                score += 0.15;
            }
            if ("MALL_BOOST".equals(abParams.get("rank_exp")) && "goods".equals(item.getSource())) {
                score += 0.05;
            }
            if ("ad".equals(item.getSource())) {
                score -= 0.03;
            }
            item.setScore(score);
        }

        return items.stream()
                .sorted(Comparator.comparingDouble(Item::getScore).reversed())
                .limit(limit)
                .toList();
    }
}
