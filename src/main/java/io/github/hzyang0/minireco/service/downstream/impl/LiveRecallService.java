package io.github.hzyang0.minireco.service.downstream.impl;

import io.github.hzyang0.minireco.domain.AttrName;
import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.domain.UserFeature;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.downstream.RecallService;
import io.github.hzyang0.minireco.util.SimulatedLatency;

import java.util.ArrayList;
import java.util.List;

public class LiveRecallService implements RecallService {
    @Override
    public String source() {
        return "live";
    }

    @Override
    public List<Item> recall(RecommendContext context) {
        SimulatedLatency.sleepMs(35);
        UserFeature feature = context.getUserFeature();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long id = 20_000L + feature.getUserId() % 1_000 * 100 + i;
            String category = i % 2 == 0 ? feature.getPreferredCategory() : "fashion";
            Item item = new Item(
                    id,
                    "直播间-" + category + "-" + i,
                    source(),
                    category,
                    0.52 + i * 0.015
            );
            item.putAttr(AttrName.ROOM_ID, String.valueOf(900_000L + i));
            item.putAttr(AttrName.RECALL_REASON, "live_hot");
            items.add(item);
        }
        return items;
    }
}
