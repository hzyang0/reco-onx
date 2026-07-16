package com.interview.minireco.grpc;

import com.interview.minireco.domain.AttrName;
import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.grpc.client.GrpcAdRecallService;
import com.interview.minireco.grpc.client.GrpcGoodsRecallService;
import com.interview.minireco.grpc.client.GrpcLiveRecallService;
import com.interview.minireco.grpc.server.AdRecallGrpcService;
import com.interview.minireco.grpc.server.GoodsRecallGrpcService;
import com.interview.minireco.grpc.server.LiveRecallGrpcService;
import com.interview.minireco.proto.goods.GoodsRecallRequestPb;
import com.interview.minireco.proto.goods.GoodsRecallResponsePb;
import com.interview.minireco.proto.goods.GoodsRecallRpcGrpc;
import com.interview.minireco.resilience.DownstreamTimeoutException;
import com.interview.minireco.service.context.RecommendContext;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcRecallIntegrationTest {
    @Test
    void threeClientsShouldCallRealSocketServerAndConvertResponses() throws Exception {
        Server server = NettyServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(
                        new GoodsRecallGrpcService(),
                        GrpcRequestMetadata.serverInterceptor()
                ))
                .addService(ServerInterceptors.intercept(
                        new LiveRecallGrpcService(),
                        GrpcRequestMetadata.serverInterceptor()
                ))
                .addService(ServerInterceptors.intercept(
                        new AdRecallGrpcService(),
                        GrpcRequestMetadata.serverInterceptor()
                ))
                .build()
                .start();
        String target = "localhost:" + server.getPort();

        try (GrpcGoodsRecallService goods = new GrpcGoodsRecallService(target, 1_000);
             GrpcLiveRecallService live = new GrpcLiveRecallService(target, 1_000);
             GrpcAdRecallService ad = new GrpcAdRecallService(target, 1_000)) {
            RecommendContext context = context("grpc-socket-request");

            List<Item> goodsItems = goods.recall(context);
            List<Item> liveItems = live.recall(context);
            List<Item> adItems = ad.recall(context);

            assertEquals(12, goodsItems.size());
            assertEquals(8, liveItems.size());
            assertEquals(5, adItems.size());
            assertEquals("preferred_category", goodsItems.get(0)
                    .findAttr(AttrName.RECALL_REASON).orElseThrow());
            assertTrue(liveItems.get(0).findAttr(AttrName.ROOM_ID).isPresent());
            assertTrue(adItems.get(0).findAttr(AttrName.CREATIVE_ID).isPresent());
            assertEquals("grpc", context.buildDebugSnapshot().get("recallTransport"));
        } finally {
            server.shutdownNow().awaitTermination();
        }
    }

    @Test
    void requestIdShouldTravelInGrpcMetadata() throws Exception {
        AtomicReference<String> receivedRequestId = new AtomicReference<>();
        GoodsRecallRpcGrpc.GoodsRecallRpcImplBase capturingService =
                new GoodsRecallRpcGrpc.GoodsRecallRpcImplBase() {
                    @Override
                    public void recall(
                            GoodsRecallRequestPb request,
                            StreamObserver<GoodsRecallResponsePb> responseObserver
                    ) {
                        receivedRequestId.set(GrpcRequestMetadata.currentRequestId());
                        responseObserver.onNext(GoodsRecallResponsePb.getDefaultInstance());
                        responseObserver.onCompleted();
                    }
                };
        Server server = NettyServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(
                        capturingService,
                        GrpcRequestMetadata.serverInterceptor()
                ))
                .build()
                .start();

        try (GrpcGoodsRecallService client = new GrpcGoodsRecallService(
                "localhost:" + server.getPort(),
                1_000
        )) {
            client.recall(context("request-propagated-123"));
            assertEquals("request-propagated-123", receivedRequestId.get());
        } finally {
            server.shutdownNow().awaitTermination();
        }
    }

    @Test
    void grpcDeadlineShouldBecomeExistingDownstreamTimeoutException() throws Exception {
        GoodsRecallRpcGrpc.GoodsRecallRpcImplBase slowService =
                new GoodsRecallRpcGrpc.GoodsRecallRpcImplBase() {
                    @Override
                    public void recall(
                            GoodsRecallRequestPb request,
                            StreamObserver<GoodsRecallResponsePb> responseObserver
                    ) {
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        responseObserver.onNext(GoodsRecallResponsePb.getDefaultInstance());
                        responseObserver.onCompleted();
                    }
                };
        Server server = NettyServerBuilder.forPort(0).addService(slowService).build().start();

        try (GrpcGoodsRecallService client = new GrpcGoodsRecallService(
                "localhost:" + server.getPort(),
                30
        )) {
            assertThrows(DownstreamTimeoutException.class, () ->
                    client.recall(context("deadline-request"))
            );
        } finally {
            server.shutdownNow().awaitTermination();
        }
    }

    private RecommendContext context(String requestId) {
        RecommendContext context = new RecommendContext(
                requestId,
                new RecommendRequest(123L, "mall", 10)
        );
        context.setUserFeature(new UserFeature(123L, false, "home", 28));
        return context;
    }
}
