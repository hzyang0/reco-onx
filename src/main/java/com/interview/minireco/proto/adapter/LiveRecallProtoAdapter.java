package com.interview.minireco.proto.adapter;

import com.interview.minireco.proto.internal.InternalItemPb;
import com.interview.minireco.proto.internal.InternalItemTypePb;
import com.interview.minireco.proto.internal.InternalRecallResultPb;
import com.interview.minireco.proto.live.LiveFeaturePb;
import com.interview.minireco.proto.live.LiveRecallItemPb;
import com.interview.minireco.proto.live.LiveRecallResponsePb;

public final class LiveRecallProtoAdapter {
    private LiveRecallProtoAdapter() {
    }

    public static InternalRecallResultPb toInternal(LiveRecallResponsePb response) {
        InternalRecallResultPb.Builder result = InternalRecallResultPb.newBuilder();
        for (LiveRecallItemPb sourceItem : response.getItemsList()) {
            InternalItemPb.Builder item = InternalItemPb.newBuilder()
                    .setItemId(sourceItem.getProductId())
                    .setTitle(sourceItem.getRoomTitle())
                    .setSource("live")
                    .setCategory(sourceItem.getProductCategory())
                    .setScore(sourceItem.getPredictionScore())
                    .setItemType(InternalItemTypePb.LIVE)
                    .putAttrs("room_id", Long.toString(sourceItem.getRoomId()));
            for (LiveFeaturePb feature : sourceItem.getFeaturesList()) {
                ProtoAttrMapper.putIfValid(item, feature.getFeatureKey(), feature.getFeatureValue());
            }
            result.addItems(item);
        }
        return result.build();
    }
}
