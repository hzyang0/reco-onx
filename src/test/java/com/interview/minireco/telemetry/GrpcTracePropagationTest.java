package com.interview.minireco.telemetry;

import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.grpc.GrpcRequestMetadata;
import com.interview.minireco.grpc.client.GrpcGoodsRecallService;
import com.interview.minireco.grpc.server.GoodsRecallGrpcService;
import com.interview.minireco.service.context.RecommendContext;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcTracePropagationTest {
    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void setUpTelemetry() {
        GlobalOpenTelemetry.resetForTest();
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();
    }

    @AfterEach
    void tearDownTelemetry() {
        tracerProvider.close();
        exporter.reset();
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void clientAndServerSpansShouldShareTheHttpParentTrace() throws Exception {
        Server server = NettyServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(
                        new GoodsRecallGrpcService(),
                        GrpcRequestMetadata.serverInterceptor(),
                        GrpcTelemetry.serverInterceptor()
                ))
                .build()
                .start();
        Span httpSpan = Telemetry.tracer().spanBuilder("GET /recommend")
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        try (GrpcGoodsRecallService client = new GrpcGoodsRecallService(
                "localhost:" + server.getPort(),
                1_000
        ); Scope ignored = httpSpan.makeCurrent()) {
            RecommendContext context = new RecommendContext(
                    "trace-propagation-request",
                    new RecommendRequest(123L, "mall", 10)
            );
            context.setUserFeature(new UserFeature(123L, false, "home", 28));
            assertEquals(12, client.recall(context).size());
        } finally {
            httpSpan.end();
            server.shutdownNow().awaitTermination();
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(3, spans.size());
        assertEquals(1, spans.stream().map(SpanData::getTraceId).distinct().count());
        assertTrue(spans.stream().anyMatch(span -> span.getKind() == SpanKind.CLIENT));
        assertTrue(spans.stream().anyMatch(span -> span.getKind() == SpanKind.SERVER
                && span.getName().contains("GoodsRecallRpc")));
        SpanData clientSpan = spans.stream()
                .filter(span -> span.getKind() == SpanKind.CLIENT)
                .findFirst()
                .orElseThrow();
        SpanData serverSpan = spans.stream()
                .filter(span -> span.getName().contains("GoodsRecallRpc"))
                .findFirst()
                .orElseThrow();
        assertEquals(clientSpan.getSpanId(), serverSpan.getParentSpanId());
    }
}
