package com.interview.minireco.proto.adapter;

import com.interview.minireco.proto.ad.AdExtensionPb;
import com.interview.minireco.proto.ad.AdRecallItemPb;
import com.interview.minireco.proto.ad.AdRecallResponsePb;
import com.interview.minireco.proto.internal.InternalItemPb;
import com.interview.minireco.proto.internal.InternalItemTypePb;
import com.interview.minireco.proto.internal.InternalRecallResultPb;

public final class AdRecallProtoAdapter {
    private static final double SCORE_SCALE = 1_000_000.0;

    private AdRecallProtoAdapter() {
    }

    public static InternalRecallResultPb toInternal(AdRecallResponsePb response) {
        InternalRecallResultPb.Builder result = InternalRecallResultPb.newBuilder();
        for (AdRecallItemPb sourceItem : response.getItemsList()) {
            InternalItemPb.Builder item = InternalItemPb.newBuilder()
                    .setItemId(sourceItem.getPromotedGoodsId())
                    .setTitle(sourceItem.getCopywriting())
                    .setSource("ad")
                    .setCategory(sourceItem.getIndustry())
                    .setScore(sourceItem.getScoreMicros() / SCORE_SCALE)
                    .setItemType(InternalItemTypePb.AD)
                    .putAttrs("creative_id", Long.toString(sourceItem.getCreativeId()));
            for (AdExtensionPb extension : sourceItem.getExtensionsList()) {
                ProtoAttrMapper.putIfValid(item, extension.getExtensionName(), extension.getExtensionValue());
            }
            result.addItems(item);
        }
        return result.build();
    }
}
