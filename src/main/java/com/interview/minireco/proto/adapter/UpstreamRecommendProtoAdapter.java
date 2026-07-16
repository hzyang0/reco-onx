package com.interview.minireco.proto.adapter;

import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.RecommendResponse;
import com.interview.minireco.proto.upstream.UpstreamRecommendItemPb;
import com.interview.minireco.proto.upstream.UpstreamRecommendResponsePb;

public final class UpstreamRecommendProtoAdapter {
    private UpstreamRecommendProtoAdapter() {
    }

    public static UpstreamRecommendResponsePb fromDomain(RecommendResponse response) {
        UpstreamRecommendResponsePb.Builder result = UpstreamRecommendResponsePb.newBuilder()
                .setRequestId(response.getRequestId())
                .setUserId(response.getUserId())
                .setScene(response.getScene())
                .setCostMs(response.getCostMs());

        for (Item sourceItem : response.getItems()) {
            UpstreamRecommendItemPb.Builder item = UpstreamRecommendItemPb.newBuilder()
                    .setId(sourceItem.getItemId())
                    .setDisplayTitle(sourceItem.getTitle())
                    .setItemType(sourceItem.getSource())
                    .setRankScore(sourceItem.getScore())
                    .putAttributes("category", sourceItem.getCategory());
            sourceItem.getAttrs().forEach((name, value) ->
                    item.putAttributes(name.key(), value.getValue())
            );
            result.addItems(item);
        }
        return result.build();
    }
}
