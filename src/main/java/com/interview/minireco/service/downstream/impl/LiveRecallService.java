package com.interview.minireco.service.downstream.impl;

import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.service.downstream.RecallService;
import com.interview.minireco.util.SimulatedLatency;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LiveRecallService implements RecallService {
    @Override
    public String source() {
        return "live";
    }

    @Override
    public List<Item> recall(Map<String, Object> context) {
        SimulatedLatency.sleepMs(35);
        UserFeature feature = (UserFeature) context.get("user_feature");
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long id = 20_000L + feature.getUserId() % 1000 * 100 + i;
            String category = i % 2 == 0 ? feature.getPreferredCategory() : "fashion";
            Item item = new Item(id, "直播间-" + category + "-" + i, source(), category, 0.52 + i * 0.015);
            item.putAttr("recall_reason", "live_hot");
            items.add(item);
        }
        return items;
    }
}
