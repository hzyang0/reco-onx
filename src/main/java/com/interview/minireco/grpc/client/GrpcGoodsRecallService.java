package com.interview.minireco.grpc.client;

import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.proto.adapter.GoodsRecallProtoAdapter;
import com.interview.minireco.proto.adapter.InternalItemDomainAdapter;
import com.interview.minireco.proto.goods.GoodsRecallRequestPb;
import com.interview.minireco.proto.goods.GoodsRecallResponsePb;
import com.interview.minireco.proto.goods.GoodsRecallRpcGrpc;
import com.interview.minireco.service.context.RecommendContext;

import java.util.List;

public final class GrpcGoodsRecallService extends AbstractGrpcRecallService {
    public GrpcGoodsRecallService(String target, long deadlineMs) {
        super("goods", target, deadlineMs);
    }

    @Override
    public List<Item> recall(RecommendContext context) {
        UserFeature feature = context.getUserFeature();
        GoodsRecallRequestPb request = GoodsRecallRequestPb.newBuilder()
                .setUserId(feature.getUserId())
                .setPreferredCategory(feature.getPreferredCategory())
                .setScene(context.getScene())
                .build();
        GoodsRecallResponsePb response = invoke(
                context,
                GoodsRecallRpcGrpc.newBlockingStub(channel()),
                stub -> stub.recall(request)
        );
        return InternalItemDomainAdapter.toDomain(GoodsRecallProtoAdapter.toInternal(response));
    }
}
