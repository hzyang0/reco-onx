package com.interview.minireco.proto;

import com.interview.minireco.proto.ad.AdExtensionPb;
import com.interview.minireco.proto.ad.AdRecallItemPb;
import com.interview.minireco.proto.ad.AdRecallResponsePb;
import com.interview.minireco.proto.ad.AdRecallRpcGrpc;
import com.interview.minireco.proto.adapter.AdRecallProtoAdapter;
import com.interview.minireco.proto.adapter.GoodsRecallProtoAdapter;
import com.interview.minireco.proto.adapter.InternalItemDomainAdapter;
import com.interview.minireco.proto.adapter.LiveRecallProtoAdapter;
import com.interview.minireco.proto.goods.GoodsAttributePb;
import com.interview.minireco.proto.goods.GoodsRecallItemPb;
import com.interview.minireco.proto.goods.GoodsRecallResponsePb;
import com.interview.minireco.proto.goods.GoodsRecallRpcGrpc;
import com.interview.minireco.proto.internal.InternalRecallResultPb;
import com.interview.minireco.proto.live.LiveFeaturePb;
import com.interview.minireco.proto.live.LiveRecallItemPb;
import com.interview.minireco.proto.live.LiveRecallResponsePb;
import com.interview.minireco.proto.live.LiveRecallRpcGrpc;
import com.interview.minireco.proto.upstream.UpstreamRecommendResponsePb;

public final class ProtoRuntimeWarmup {
    private static volatile int initializedItemCount;

    private ProtoRuntimeWarmup() {
    }

    public static void initialize() {
        InternalRecallResultPb goods = GoodsRecallProtoAdapter.toInternal(
                GoodsRecallResponsePb.newBuilder()
                        .addItems(GoodsRecallItemPb.newBuilder()
                                .setGoodsId(1L)
                                .addAttributes(GoodsAttributePb.newBuilder()
                                        .setName("recall_reason")
                                        .setValue("warmup")))
                        .build()
        );
        InternalRecallResultPb live = LiveRecallProtoAdapter.toInternal(
                LiveRecallResponsePb.newBuilder()
                        .addItems(LiveRecallItemPb.newBuilder()
                                .setRoomId(2L)
                                .setProductId(3L)
                                .addFeatures(LiveFeaturePb.newBuilder()
                                        .setFeatureKey("recall_reason")
                                        .setFeatureValue("warmup")))
                        .build()
        );
        InternalRecallResultPb ad = AdRecallProtoAdapter.toInternal(
                AdRecallResponsePb.newBuilder()
                        .addItems(AdRecallItemPb.newBuilder()
                                .setCreativeId(4L)
                                .setPromotedGoodsId(5L)
                                .addExtensions(AdExtensionPb.newBuilder()
                                        .setExtensionName("recall_reason")
                                        .setExtensionValue("warmup")))
                        .build()
        );
        initializedItemCount = InternalItemDomainAdapter.toDomain(goods).size()
                + InternalItemDomainAdapter.toDomain(live).size()
                + InternalItemDomainAdapter.toDomain(ad).size();
        GoodsRecallRpcGrpc.getRecallMethod();
        LiveRecallRpcGrpc.getRecallMethod();
        AdRecallRpcGrpc.getRecallMethod();
        UpstreamRecommendResponsePb.getDefaultInstance();
    }

    static int initializedItemCount() {
        return initializedItemCount;
    }
}
