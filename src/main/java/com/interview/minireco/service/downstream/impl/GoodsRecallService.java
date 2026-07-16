package com.interview.minireco.service.downstream.impl;

import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.RecallService;
import com.interview.minireco.util.SimulatedLatency;

import java.util.ArrayList;
import java.util.List;

public class GoodsRecallService implements RecallService {
    @Override
    public String source() {
        return "goods";
    }

    @Override
    public List<Item> recall(RecommendContext context) {
        SimulatedLatency.sleepMs(45);
        UserFeature feature = context.getUserFeature();
        String category = feature.getPreferredCategory();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            long id = 10_000L + feature.getUserId() % 1000 * 100 + i;
            Item item = new Item(id, "商品-" + category + "-" + i, source(), category, 0.60 + i * 0.01);
            item.putAttr("recall_reason", "preferred_category");
            items.add(item);
        }
        return items;
    }
}
