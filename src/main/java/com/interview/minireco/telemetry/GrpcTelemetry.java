package com.interview.minireco.telemetry;

import io.grpc.Contexts;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.concurrent.atomic.AtomicBoolean;

public final class GrpcTelemetry {
    private static final io.grpc.Context.Key<Context> OTEL_CONTEXT = io.grpc.Context.key("otel-context");
    private static final TextMapSetter<Metadata> METADATA_SETTER = (carrier, key, value) -> {
        if (carrier != null && value != null) {
            carrier.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
        }
    };
    private static final TextMapGetter<Metadata> METADATA_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Metadata carrier) {
            return carrier.keys();
        }

        @Override
        public String get(Metadata carrier, String key) {
            if (carrier == null) {
                return null;
            }
            return carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
        }
    };

    private GrpcTelemetry() {
    }

    public static void inject(Context context, Metadata metadata) {
        GlobalOpenTelemetry.getPropagators().getTextMapPropagator().inject(
                context,
                metadata,
                METADATA_SETTER
        );
    }

    public static Context currentContext() {
        Context context = OTEL_CONTEXT.get();
        return context == null ? Context.root() : context;
    }

    public static ServerInterceptor serverInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next
            ) {
                String method = call.getMethodDescriptor().getFullMethodName();
                Context parent = GlobalOpenTelemetry.getPropagators()
                        .getTextMapPropagator()
                        .extract(Context.root(), headers, METADATA_GETTER);
                Span span = Telemetry.tracer().spanBuilder(method)
                        .setParent(parent)
                        .setSpanKind(SpanKind.SERVER)
                        .startSpan();
                span.setAttribute("rpc.system", "grpc");
                span.setAttribute("rpc.method", method);
                Context traceContext = parent.with(span);
                AtomicBoolean ended = new AtomicBoolean(false);
                ServerCall<ReqT, RespT> tracedCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                    @Override
                    public void close(Status status, Metadata trailers) {
                        span.setAttribute("rpc.grpc.status_code", status.getCode().value());
                        if (!status.isOk()) {
                            span.setStatus(StatusCode.ERROR, status.getDescription() == null
                                    ? status.getCode().name()
                                    : status.getDescription());
                        }
                        if (ended.compareAndSet(false, true)) {
                            span.end();
                        }
                        super.close(status, trailers);
                    }
                };
                io.grpc.Context grpcContext = io.grpc.Context.current().withValue(
                        OTEL_CONTEXT,
                        traceContext
                );
                try {
                    ServerCall.Listener<ReqT> listener = Contexts.interceptCall(
                            grpcContext,
                            tracedCall,
                            headers,
                            next
                    );
                    return contextAware(listener, traceContext);
                } catch (RuntimeException e) {
                    span.recordException(e);
                    span.setStatus(StatusCode.ERROR);
                    if (ended.compareAndSet(false, true)) {
                        span.end();
                    }
                    throw e;
                }
            }
        };
    }

    private static <ReqT> ServerCall.Listener<ReqT> contextAware(
            ServerCall.Listener<ReqT> delegate,
            Context context
    ) {
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT message) {
                run(context, () -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                run(context, super::onHalfClose);
            }

            @Override
            public void onCancel() {
                run(context, super::onCancel);
            }

            @Override
            public void onComplete() {
                run(context, super::onComplete);
            }

            @Override
            public void onReady() {
                run(context, super::onReady);
            }
        };
    }

    private static void run(Context context, Runnable action) {
        try (Scope ignored = context.makeCurrent()) {
            action.run();
        }
    }
}
