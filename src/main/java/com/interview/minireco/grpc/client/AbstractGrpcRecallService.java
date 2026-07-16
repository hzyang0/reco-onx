package com.interview.minireco.grpc.client;

import com.interview.minireco.grpc.GrpcRequestMetadata;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.resilience.DownstreamCallException;
import com.interview.minireco.resilience.DownstreamTimeoutException;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.RecallService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

abstract class AbstractGrpcRecallService implements RecallService, AutoCloseable {
    private final String source;
    private final String target;
    private final long deadlineMs;
    private final MetricsRegistry metricsRegistry;
    private final ManagedChannel channel;

    protected AbstractGrpcRecallService(String source, String target, long deadlineMs) {
        this(
                source,
                target,
                deadlineMs,
                MetricsRegistry.global(),
                ManagedChannelBuilder.forTarget(target).usePlaintext().build()
        );
    }

    protected AbstractGrpcRecallService(
            String source,
            String target,
            long deadlineMs,
            MetricsRegistry metricsRegistry,
            ManagedChannel channel
    ) {
        if (deadlineMs <= 0) {
            throw new IllegalArgumentException("gRPC deadline must be positive");
        }
        this.source = source;
        this.target = target;
        this.deadlineMs = deadlineMs;
        this.metricsRegistry = metricsRegistry;
        this.channel = channel;
        this.channel.getState(true);
    }

    @Override
    public final String source() {
        return source;
    }

    protected final ManagedChannel channel() {
        return channel;
    }

    protected final <S extends AbstractStub<S>, R> R invoke(
            RecommendContext context,
            S stub,
            Function<S, R> rpc
    ) {
        Metadata metadata = new Metadata();
        metadata.put(GrpcRequestMetadata.REQUEST_ID_HEADER, context.getRequestId());
        S configuredStub = stub
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
        long start = System.nanoTime();
        try {
            R response = rpc.apply(configuredStub);
            record("success", elapsedMs(start));
            context.putDebug("recallTransport", "grpc");
            return response;
        } catch (StatusRuntimeException e) {
            String status = e.getStatus().getCode().name().toLowerCase();
            record(status, elapsedMs(start));
            if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                throw new DownstreamTimeoutException(source, deadlineMs);
            }
            throw new DownstreamCallException(source, e);
        }
    }

    @Override
    public void close() {
        channel.shutdownNow();
    }

    final String target() {
        return target;
    }

    private void record(String status, long costMs) {
        Map<String, String> tags = Map.of(
                "source", source,
                "status", status,
                "target", target
        );
        metricsRegistry.increment("grpc.client.call", tags);
        metricsRegistry.recordTimer("grpc.client.call.cost", tags, costMs);
    }

    private long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
