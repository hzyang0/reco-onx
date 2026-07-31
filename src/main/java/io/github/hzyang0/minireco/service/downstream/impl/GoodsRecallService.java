package io.github.hzyang0.minireco.service.downstream.impl;

import io.github.hzyang0.minireco.domain.AttrName;
import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.domain.UserFeature;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.downstream.RecallService;
import io.github.hzyang0.minireco.util.SimulatedLatency;

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
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            long id = 10_000L + feature.getUserId() % 1_000 * 100 + i;
            Item item = new Item(
                    id,
                    "商品-" + feature.getPreferredCategory() + "-" + i,
                    source(),
                    feature.getPreferredCategory(),
                    0.60 + i * 0.01
            );
            item.putAttr(AttrName.RECALL_REASON, "preferred_category");
            items.add(item);
        }
        return items;
    }
}
