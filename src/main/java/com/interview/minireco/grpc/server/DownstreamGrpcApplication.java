package com.interview.minireco.grpc.server;

import com.interview.minireco.grpc.GrpcRequestMetadata;
import com.interview.minireco.telemetry.GrpcTelemetry;
import com.interview.minireco.telemetry.Telemetry;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class DownstreamGrpcApplication {
    private DownstreamGrpcApplication() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException(
                    "usage: DownstreamGrpcApplication <goods|live|ad> [port]"
            );
        }
        String source = args[0].trim().toLowerCase(Locale.ROOT);
        Telemetry.initialize("mini-reco-" + source);
        int port = args.length == 2 ? Integer.parseInt(args[1]) : defaultPort(source);
        BindableService service = service(source);
        String serviceName = service.bindService().getServiceDescriptor().getName();
        HealthStatusManager health = new HealthStatusManager();
        Server server = NettyServerBuilder.forPort(port)
                .addService(ServerInterceptors.intercept(
                        service,
                        GrpcRequestMetadata.serverInterceptor(),
                        GrpcTelemetry.serverInterceptor()
                ))
                .addService(health.getHealthService())
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();
        health.setStatus("", ServingStatus.SERVING);
        health.setStatus(serviceName, ServingStatus.SERVING);

        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> shutdown(server, health),
                source + "-grpc-shutdown"
        ));
        System.out.printf(
                "GRPC_DOWNSTREAM_READY source=%s service=%s port=%d health=SERVING reflection=enabled%n",
                source,
                serviceName,
                port
        );
        server.awaitTermination();
    }

    private static BindableService service(String source) {
        return switch (source) {
            case "goods" -> new GoodsRecallGrpcService();
            case "live" -> new LiveRecallGrpcService();
            case "ad" -> new AdRecallGrpcService();
            default -> throw new IllegalArgumentException("unknown gRPC recall source: " + source);
        };
    }

    private static int defaultPort(String source) {
        return switch (source) {
            case "goods" -> 19001;
            case "live" -> 19002;
            case "ad" -> 19003;
            default -> throw new IllegalArgumentException("unknown gRPC recall source: " + source);
        };
    }

    private static void shutdown(Server server, HealthStatusManager health) {
        health.enterTerminalState();
        server.shutdown();
        try {
            if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
