package com.interview.minireco.grpc.server;

import com.interview.minireco.grpc.GrpcRequestMetadata;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.observability.StructuredLogger;
import com.interview.minireco.proto.live.LiveFeaturePb;
import com.interview.minireco.proto.live.LiveRecallItemPb;
import com.interview.minireco.proto.live.LiveRecallRequestPb;
import com.interview.minireco.proto.live.LiveRecallResponsePb;
import com.interview.minireco.proto.live.LiveRecallRpcGrpc;
import com.interview.minireco.util.SimulatedLatency;
import io.grpc.stub.StreamObserver;

import java.util.Map;

public final class LiveRecallGrpcService extends LiveRecallRpcGrpc.LiveRecallRpcImplBase {
    private static final StructuredLogger LOGGER = StructuredLogger.getLogger(LiveRecallGrpcService.class);

    @Override
    public void recall(
            LiveRecallRequestPb request,
            StreamObserver<LiveRecallResponsePb> responseObserver
    ) {
        String requestId = GrpcRequestMetadata.currentRequestId();
        long start = System.nanoTime();
        SimulatedLatency.sleepMs(35);
        LiveRecallResponsePb.Builder response = LiveRecallResponsePb.newBuilder();
        for (int i = 0; i < 8; i++) {
            long id = 20_000L + request.getUserId() % 1000 * 100 + i;
            String category = i % 2 == 0 ? request.getPreferredCategory() : "fashion";
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
        LiveRecallResponsePb result = response.build();
        responseObserver.onNext(result);
        responseObserver.onCompleted();
        record(requestId, result.getItemsCount(), start);
    }

    private void record(String requestId, int itemCount, long startNanos) {
        long costMs = (System.nanoTime() - startNanos) / 1_000_000;
        MetricsRegistry.global().recordTimer("grpc.server.call.cost", Map.of("source", "live"), costMs);
        LOGGER.info(requestId, "grpc_recall_completed", () -> Map.of(
                "source", "live",
                "itemCount", itemCount,
                "costMs", costMs
        ));
    }
}
