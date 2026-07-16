package com.interview.minireco.service.downstream.impl;

import com.interview.minireco.domain.AttrName;
import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.RecallService;
import com.interview.minireco.util.SimulatedLatency;

import java.util.ArrayList;
import java.util.List;

public class AdRecallService implements RecallService {
    @Override
    public String source() {
        return "ad";
    }

    @Override
    public List<Item> recall(RecommendContext context) {
        SimulatedLatency.sleepMs(20);
        UserFeature feature = context.getUserFeature();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long id = 30_000L + feature.getUserId() % 1000 * 100 + i;
            Item item = new Item(id, "广告商品-" + i, source(), "digital", 0.48 + i * 0.02);
            item.putAttr(AttrName.RECALL_REASON, "commercial");
            items.add(item);
        }
        return items;
    }
}
