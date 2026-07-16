package com.interview.minireco.grpc.client;

import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.proto.adapter.InternalItemDomainAdapter;
import com.interview.minireco.proto.adapter.LiveRecallProtoAdapter;
import com.interview.minireco.proto.live.LiveRecallRequestPb;
import com.interview.minireco.proto.live.LiveRecallResponsePb;
import com.interview.minireco.proto.live.LiveRecallRpcGrpc;
import com.interview.minireco.service.context.RecommendContext;

import java.util.List;

public final class GrpcLiveRecallService extends AbstractGrpcRecallService {
    public GrpcLiveRecallService(String target, long deadlineMs) {
        super("live", target, deadlineMs);
    }

    @Override
    public List<Item> recall(RecommendContext context) {
        UserFeature feature = context.getUserFeature();
        LiveRecallRequestPb request = LiveRecallRequestPb.newBuilder()
                .setUserId(feature.getUserId())
                .setPreferredCategory(feature.getPreferredCategory())
                .setScene(context.getScene())
                .build();
        LiveRecallResponsePb response = invoke(
                context,
                LiveRecallRpcGrpc.newBlockingStub(channel()),
                stub -> stub.recall(request)
        );
        return InternalItemDomainAdapter.toDomain(LiveRecallProtoAdapter.toInternal(response));
    }
}
