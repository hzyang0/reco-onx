package com.interview.minireco.telemetry.collector;

import com.interview.minireco.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class TraceCollectorApplication {
    private TraceCollectorApplication() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int grpcPort = args.length > 0 ? Integer.parseInt(args[0]) : 4317;
        int httpPort = args.length > 1 ? Integer.parseInt(args[1]) : 16686;
        TraceStore store = new TraceStore();
        Server grpcServer = NettyServerBuilder.forPort(grpcPort)
                .addService(new TraceServiceGrpc.TraceServiceImplBase() {
                    @Override
                    public void export(
                            ExportTraceServiceRequest request,
                            StreamObserver<ExportTraceServiceResponse> responseObserver
                    ) {
                        store.record(request);
                        responseObserver.onNext(ExportTraceServiceResponse.getDefaultInstance());
                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();
        HttpServer queryServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
        queryServer.createContext("/health", exchange -> writeJson(exchange, 200, JsonUtil.mapToJson(Map.of(
                "status", "UP",
                "service", "mini-reco-trace-collector",
                "traceCount", store.traceCount()
        ))));
        queryServer.createContext("/api/traces", exchange -> handleTraceQuery(exchange, store));
        queryServer.setExecutor(Executors.newFixedThreadPool(4));
        queryServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            queryServer.stop(1);
            grpcServer.shutdown();
            try {
                if (!grpcServer.awaitTermination(2, TimeUnit.SECONDS)) {
                    grpcServer.shutdownNow();
                }
            } catch (InterruptedException e) {
                grpcServer.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }, "trace-collector-shutdown"));
        System.out.printf("OTLP_TRACE_COLLECTOR_READY grpcPort=%d httpPort=%d%n", grpcPort, httpPort);
        grpcServer.awaitTermination();
    }

    private static void handleTraceQuery(HttpExchange exchange, TraceStore store) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, JsonUtil.errorToJson("method not allowed"));
            return;
        }
        String prefix = "/api/traces/";
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(prefix) || path.length() <= prefix.length()) {
            writeJson(exchange, 400, JsonUtil.errorToJson("traceId path parameter is required"));
            return;
        }
        String traceId = path.substring(prefix.length());
        Map<String, Object> trace = store.find(traceId);
        writeJson(exchange, Boolean.TRUE.equals(trace.get("found")) ? 200 : 404, JsonUtil.mapToJson(trace));
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
