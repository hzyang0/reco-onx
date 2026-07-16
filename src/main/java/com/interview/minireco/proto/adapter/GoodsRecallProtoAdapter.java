package com.interview.minireco.proto.adapter;

import com.interview.minireco.proto.goods.GoodsAttributePb;
import com.interview.minireco.proto.goods.GoodsRecallItemPb;
import com.interview.minireco.proto.goods.GoodsRecallResponsePb;
import com.interview.minireco.proto.internal.InternalItemPb;
import com.interview.minireco.proto.internal.InternalItemTypePb;
import com.interview.minireco.proto.internal.InternalRecallResultPb;

public final class GoodsRecallProtoAdapter {
    private GoodsRecallProtoAdapter() {
    }

    public static InternalRecallResultPb toInternal(GoodsRecallResponsePb response) {
        InternalRecallResultPb.Builder result = InternalRecallResultPb.newBuilder();
        for (GoodsRecallItemPb sourceItem : response.getItemsList()) {
            InternalItemPb.Builder item = InternalItemPb.newBuilder()
                    .setItemId(sourceItem.getGoodsId())
                    .setTitle(sourceItem.getGoodsTitle())
                    .setSource("goods")
                    .setCategory(sourceItem.getCategory())
                    .setScore(sourceItem.getRelevanceScore())
                    .setItemType(InternalItemTypePb.GOODS);
            for (GoodsAttributePb attribute : sourceItem.getAttributesList()) {
                ProtoAttrMapper.putIfValid(item, attribute.getName(), attribute.getValue());
            }
            result.addItems(item);
        }
        return result.build();
    }
}
