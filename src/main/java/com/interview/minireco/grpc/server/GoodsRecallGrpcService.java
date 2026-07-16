package com.interview.minireco.grpc.server;

import com.interview.minireco.grpc.GrpcRequestMetadata;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.observability.StructuredLogger;
import com.interview.minireco.proto.goods.GoodsAttributePb;
import com.interview.minireco.proto.goods.GoodsRecallItemPb;
import com.interview.minireco.proto.goods.GoodsRecallRequestPb;
import com.interview.minireco.proto.goods.GoodsRecallResponsePb;
import com.interview.minireco.proto.goods.GoodsRecallRpcGrpc;
import com.interview.minireco.util.SimulatedLatency;
import io.grpc.stub.StreamObserver;

import java.util.Map;

public final class GoodsRecallGrpcService extends GoodsRecallRpcGrpc.GoodsRecallRpcImplBase {
    private static final StructuredLogger LOGGER = StructuredLogger.getLogger(GoodsRecallGrpcService.class);

    @Override
    public void recall(
            GoodsRecallRequestPb request,
            StreamObserver<GoodsRecallResponsePb> responseObserver
    ) {
        String requestId = GrpcRequestMetadata.currentRequestId();
        long start = System.nanoTime();
        SimulatedLatency.sleepMs(45);
        GoodsRecallResponsePb.Builder response = GoodsRecallResponsePb.newBuilder();
        for (int i = 0; i < 12; i++) {
            long id = 10_000L + request.getUserId() % 1000 * 100 + i;
            response.addItems(GoodsRecallItemPb.newBuilder()
                    .setGoodsId(id)
                    .setGoodsTitle("商品-" + request.getPreferredCategory() + "-" + i)
                    .setCategory(request.getPreferredCategory())
                    .setRelevanceScore(0.60 + i * 0.01)
                    .addAttributes(GoodsAttributePb.newBuilder()
                            .setName("recall_reason")
                            .setValue("preferred_category")));
        }
        GoodsRecallResponsePb result = response.build();
        responseObserver.onNext(result);
        responseObserver.onCompleted();
        record(requestId, result.getItemsCount(), start);
    }

    private void record(String requestId, int itemCount, long startNanos) {
        long costMs = (System.nanoTime() - startNanos) / 1_000_000;
        MetricsRegistry.global().recordTimer("grpc.server.call.cost", Map.of("source", "goods"), costMs);
        LOGGER.info(requestId, "grpc_recall_completed", () -> Map.of(
                "source", "goods",
                "itemCount", itemCount,
                "costMs", costMs
        ));
    }
}
