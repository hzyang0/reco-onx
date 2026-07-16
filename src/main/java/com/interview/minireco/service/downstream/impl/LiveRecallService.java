package com.interview.minireco.service.downstream.impl;

import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.proto.adapter.InternalItemDomainAdapter;
import com.interview.minireco.proto.adapter.LiveRecallProtoAdapter;
import com.interview.minireco.proto.live.LiveFeaturePb;
import com.interview.minireco.proto.live.LiveRecallItemPb;
import com.interview.minireco.proto.live.LiveRecallResponsePb;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.RecallService;
import com.interview.minireco.util.SimulatedLatency;

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
        LiveRecallResponsePb.Builder response = LiveRecallResponsePb.newBuilder();
        for (int i = 0; i < 8; i++) {
            long id = 20_000L + feature.getUserId() % 1000 * 100 + i;
            String category = i % 2 == 0 ? feature.getPreferredCategory() : "fashion";
            response.addItems(LiveRecallItemPb.newBuilder()
                    .setRoomId(900_000L + i)
                    .setProductId(id)
                    .setRoomTitle("直播间-" + category + "-" + i)
                    .setProductCategory(category)
                    .setPredictionScore((float) (0.52 + i * 0.015))
                    .addFeatures(LiveFeaturePb.newBuilder()
                            .setFeatureKey("recall_reason")
                            .setFeatureValue("live_hot")));
        }
        return InternalItemDomainAdapter.toDomain(
                LiveRecallProtoAdapter.toInternal(response.build())
        );
    }
}
