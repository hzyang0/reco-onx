package com.interview.minireco.grpc.ops;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;

import java.util.concurrent.TimeUnit;

public final class GrpcHealthProbe {
    private GrpcHealthProbe() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("usage: GrpcHealthProbe <target> [service-name]");
        }
        String target = args[0];
        String serviceName = args.length == 2 ? args[1] : "";
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        try {
            HealthCheckResponse response = HealthGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(2, TimeUnit.SECONDS)
                    .check(HealthCheckRequest.newBuilder().setService(serviceName).build());
            if (response.getStatus() != HealthCheckResponse.ServingStatus.SERVING) {
                throw new IllegalStateException(
                        "gRPC service is not serving: " + serviceName + " status=" + response.getStatus()
                );
            }
            System.out.printf("GRPC_HEALTH_OK target=%s service=%s status=%s%n", target, serviceName, response.getStatus());
        } catch (StatusRuntimeException e) {
            throw new IllegalStateException("gRPC health check failed for " + target, e);
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
