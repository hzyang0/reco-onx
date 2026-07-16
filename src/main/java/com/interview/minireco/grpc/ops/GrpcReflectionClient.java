package com.interview.minireco.grpc.ops;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class GrpcReflectionClient {
    private GrpcReflectionClient() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: GrpcReflectionClient <target>");
        }
        String target = args[0];
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<ServerReflectionResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            StreamObserver<ServerReflectionResponse> responseObserver = new StreamObserver<>() {
                @Override
                public void onNext(ServerReflectionResponse value) {
                    response.set(value);
                }

                @Override
                public void onError(Throwable throwable) {
                    error.set(throwable);
                    completed.countDown();
                }

                @Override
                public void onCompleted() {
                    completed.countDown();
                }
            };
            StreamObserver<ServerReflectionRequest> requestObserver = ServerReflectionGrpc
                    .newStub(channel)
                    .withDeadlineAfter(2, TimeUnit.SECONDS)
                    .serverReflectionInfo(responseObserver);
            requestObserver.onNext(ServerReflectionRequest.newBuilder().setListServices("").build());
            requestObserver.onCompleted();

            if (!completed.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("gRPC reflection timed out for " + target);
            }
            if (error.get() != null) {
                throw new IllegalStateException("gRPC reflection failed for " + target, error.get());
            }
            ServerReflectionResponse result = response.get();
            if (result == null || !result.hasListServicesResponse()) {
                throw new IllegalStateException("gRPC reflection returned no service list");
            }
            List<String> services = result.getListServicesResponse().getServiceList().stream()
                    .map(service -> service.getName())
                    .sorted()
                    .toList();
            services.forEach(System.out::println);
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
