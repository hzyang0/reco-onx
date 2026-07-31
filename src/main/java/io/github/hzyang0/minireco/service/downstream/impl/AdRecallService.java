package io.github.hzyang0.minireco.service.downstream.impl;

import io.github.hzyang0.minireco.domain.AttrName;
import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.domain.UserFeature;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.downstream.RecallService;
import io.github.hzyang0.minireco.util.SimulatedLatency;

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
            long id = 30_000L + feature.getUserId() % 1_000 * 100 + i;
            Item item = new Item(
                    id,
                    "广告商品-" + i,
                    source(),
                    "digital",
                    0.48 + i * 0.02
            );
            item.putAttr(AttrName.CREATIVE_ID, String.valueOf(800_000L + i));
            item.putAttr(AttrName.RECALL_REASON, "commercial");
            items.add(item);
        }
        return items;
    }
}
