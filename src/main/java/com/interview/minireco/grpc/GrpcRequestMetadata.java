package com.interview.minireco.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

public final class GrpcRequestMetadata {
    public static final Metadata.Key<String> REQUEST_ID_HEADER = Metadata.Key.of(
            "x-request-id",
            Metadata.ASCII_STRING_MARSHALLER
    );
    private static final Context.Key<String> REQUEST_ID_CONTEXT = Context.key("mini-reco-request-id");

    private GrpcRequestMetadata() {
    }

    public static String currentRequestId() {
        String requestId = REQUEST_ID_CONTEXT.get();
        return requestId == null || requestId.isBlank() ? "missing" : requestId;
    }

    public static ServerInterceptor serverInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next
            ) {
                String requestId = headers.get(REQUEST_ID_HEADER);
                Context context = Context.current().withValue(
                        REQUEST_ID_CONTEXT,
                        requestId == null ? "missing" : requestId
                );
                return Contexts.interceptCall(context, call, headers, next);
            }
        };
    }
}
