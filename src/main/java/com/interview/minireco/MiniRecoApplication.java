package com.interview.minireco;

import com.interview.minireco.http.RecommendHttpHandler;
import com.interview.minireco.observability.AlertManager;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.service.DemoWiring;
import com.interview.minireco.service.RecommendService;
import com.interview.minireco.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;

public class MiniRecoApplication {
    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);
        RecommendService recommendService = DemoWiring.createRecommendService();
        MetricsRegistry metricsRegistry = MetricsRegistry.global();
        AlertManager alertManager = new AlertManager(metricsRegistry);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/recommend", new RecommendHttpHandler(recommendService));
        server.createContext("/health", exchange -> {
            String body = JsonUtil.mapToJson(Map.of(
                    "status", "UP",
                    "service", "mini-reco-access-layer",
                    "time", Instant.now().toString()
            ));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/metrics", exchange ->
                writeJson(exchange, JsonUtil.mapToJson(metricsRegistry.snapshot()))
        );
        server.createContext("/alerts", exchange ->
                writeJson(exchange, JsonUtil.mapToJson(alertManager.snapshot()))
        );
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        System.out.printf("MiniReco service started on port %d%n", port);
        System.out.printf("Try: http://localhost:%d/recommend?userId=123&scene=mall&limit=10%n", port);
        System.out.printf("Metrics: http://localhost:%d/metrics%n", port);
        System.out.printf("Alerts: http://localhost:%d/alerts%n", port);
    }

    private static int resolvePort(String[] args) {
        if (args.length > 0) {
            return Integer.parseInt(args[0]);
        }
        String envPort = System.getenv("PORT");
        if (envPort == null || envPort.isBlank()) {
            return 8080;
        }
        return Integer.parseInt(envPort);
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            body = JsonUtil.errorToJson("method not allowed");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(405, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
