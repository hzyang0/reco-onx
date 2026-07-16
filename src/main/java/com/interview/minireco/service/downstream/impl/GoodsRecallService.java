package com.interview.minireco.service.downstream.impl;

import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.proto.adapter.GoodsRecallProtoAdapter;
import com.interview.minireco.proto.adapter.InternalItemDomainAdapter;
import com.interview.minireco.proto.goods.GoodsAttributePb;
import com.interview.minireco.proto.goods.GoodsRecallItemPb;
import com.interview.minireco.proto.goods.GoodsRecallResponsePb;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.RecallService;
import com.interview.minireco.util.SimulatedLatency;

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
        GoodsRecallResponsePb.Builder response = GoodsRecallResponsePb.newBuilder();
        for (int i = 0; i < 12; i++) {
            long id = 10_000L + feature.getUserId() % 1000 * 100 + i;
            response.addItems(GoodsRecallItemPb.newBuilder()
                    .setGoodsId(id)
                    .setGoodsTitle("商品-" + category + "-" + i)
                    .setCategory(category)
                    .setRelevanceScore(0.60 + i * 0.01)
                    .addAttributes(GoodsAttributePb.newBuilder()
                            .setName("recall_reason")
                            .setValue("preferred_category")));
        }
        return InternalItemDomainAdapter.toDomain(
                GoodsRecallProtoAdapter.toInternal(response.build())
        );
    }
}
