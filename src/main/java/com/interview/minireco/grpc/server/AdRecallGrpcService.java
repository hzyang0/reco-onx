package com.interview.minireco.grpc.server;

import com.interview.minireco.grpc.GrpcRequestMetadata;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.observability.StructuredLogger;
import com.interview.minireco.proto.ad.AdExtensionPb;
import com.interview.minireco.proto.ad.AdRecallItemPb;
import com.interview.minireco.proto.ad.AdRecallRequestPb;
import com.interview.minireco.proto.ad.AdRecallResponsePb;
import com.interview.minireco.proto.ad.AdRecallRpcGrpc;
import com.interview.minireco.util.SimulatedLatency;
import io.grpc.stub.StreamObserver;

import java.util.Map;

public final class AdRecallGrpcService extends AdRecallRpcGrpc.AdRecallRpcImplBase {
    private static final StructuredLogger LOGGER = StructuredLogger.getLogger(AdRecallGrpcService.class);

    @Override
    public void recall(
            AdRecallRequestPb request,
            StreamObserver<AdRecallResponsePb> responseObserver
    ) {
        String requestId = GrpcRequestMetadata.currentRequestId();
        long start = System.nanoTime();
        SimulatedLatency.sleepMs(20);
        AdRecallResponsePb.Builder response = AdRecallResponsePb.newBuilder();
        for (int i = 0; i < 5; i++) {
            long id = 30_000L + request.getUserId() % 1000 * 100 + i;
            response.addItems(AdRecallItemPb.newBuilder()
                    .setCreativeId(800_000L + i)
                    .setPromotedGoodsId(id)
                    .setCopywriting("广告商品-" + i)
                    .setIndustry("digital")
                    .setScoreMicros(Math.round((0.48 + i * 0.02) * 1_000_000))
                    .addExtensions(AdExtensionPb.newBuilder()
                            .setExtensionName("recall_reason")
                            .setExtensionValue("commercial")));
        }
        AdRecallResponsePb result = response.build();
        responseObserver.onNext(result);
        responseObserver.onCompleted();
        record(requestId, result.getItemsCount(), start);
    }

    private void record(String requestId, int itemCount, long startNanos) {
        long costMs = (System.nanoTime() - startNanos) / 1_000_000;
        MetricsRegistry.global().recordTimer("grpc.server.call.cost", Map.of("source", "ad"), costMs);
        LOGGER.info(requestId, "grpc_recall_completed", () -> Map.of(
                "source", "ad",
                "itemCount", itemCount,
                "costMs", costMs
        ));
    }
}
