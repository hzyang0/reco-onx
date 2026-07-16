package com.interview.minireco.observability;

import com.interview.minireco.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MetricsHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;

    private MetricsHttpServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static MetricsHttpServer start(int port, MetricsRegistry registry) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics/prometheus", new PrometheusMetricsHandler(registry));
        server.createContext("/health", exchange -> writeJson(exchange, JsonUtil.mapToJson(Map.of(
                "status", "UP",
                "service", "metrics"
        ))));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        server.setExecutor(executor);
        server.start();
        return new MetricsHttpServer(server, executor);
    }

    @Override
    public void close() {
        server.stop(1);
        executor.shutdownNow();
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
