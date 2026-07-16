package com.interview.minireco.grpc.client;

import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.proto.ad.AdRecallRequestPb;
import com.interview.minireco.proto.ad.AdRecallResponsePb;
import com.interview.minireco.proto.ad.AdRecallRpcGrpc;
import com.interview.minireco.proto.adapter.AdRecallProtoAdapter;
import com.interview.minireco.proto.adapter.InternalItemDomainAdapter;
import com.interview.minireco.service.context.RecommendContext;

import java.util.List;

public final class GrpcAdRecallService extends AbstractGrpcRecallService {
    public GrpcAdRecallService(String target, long deadlineMs) {
        super("ad", target, deadlineMs);
    }

    @Override
    public List<Item> recall(RecommendContext context) {
        UserFeature feature = context.getUserFeature();
        AdRecallRequestPb request = AdRecallRequestPb.newBuilder()
                .setUserId(feature.getUserId())
                .setPreferredCategory(feature.getPreferredCategory())
                .setScene(context.getScene())
                .build();
        AdRecallResponsePb response = invoke(
                context,
                AdRecallRpcGrpc.newBlockingStub(channel()),
                stub -> stub.recall(request)
        );
        return InternalItemDomainAdapter.toDomain(AdRecallProtoAdapter.toInternal(response));
    }
}
