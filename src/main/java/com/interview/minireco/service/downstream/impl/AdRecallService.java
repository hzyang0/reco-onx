package com.interview.minireco.service.downstream.impl;

import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.proto.ad.AdExtensionPb;
import com.interview.minireco.proto.ad.AdRecallItemPb;
import com.interview.minireco.proto.ad.AdRecallResponsePb;
import com.interview.minireco.proto.adapter.AdRecallProtoAdapter;
import com.interview.minireco.proto.adapter.InternalItemDomainAdapter;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.RecallService;
import com.interview.minireco.util.SimulatedLatency;

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
        AdRecallResponsePb.Builder response = AdRecallResponsePb.newBuilder();
        for (int i = 0; i < 5; i++) {
            long id = 30_000L + feature.getUserId() % 1000 * 100 + i;
            response.addItems(AdRecallItemPb.newBuilder()
                    .setCreativeId(800_000L + i)
                    .setPromotedGoodsId(id)
                    .setCopywriting("广告商品-" + i)
                    .setIndustry("digital")
                    .setScoreMicros(Math.round((0.48 + i * 0.02) * 1_000_000))
                    .addExtensions(AdExtensionPb.newBuilder()
                            .setExtensionName("recall_reason")
                            .setExtensionValue("commercial")));
        }
        return InternalItemDomainAdapter.toDomain(
                AdRecallProtoAdapter.toInternal(response.build())
        );
    }
}
