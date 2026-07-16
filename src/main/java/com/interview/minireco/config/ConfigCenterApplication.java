package com.interview.minireco.config;

import com.interview.minireco.degradation.DegradationLevel;
import com.interview.minireco.http.QueryStringParser;
import com.interview.minireco.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class ConfigCenterApplication {
    private ConfigCenterApplication() {
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8888;
        DynamicConfigStore store = new DynamicConfigStore();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", exchange -> write(exchange, 200, JsonUtil.mapToJson(Map.of(
                "status", "UP", "service", "mini-reco-config-center", "version", store.get().version()
        ))));
        server.createContext("/api/config", exchange -> handleConfig(exchange, store));
        server.createContext("/api/config/history", exchange -> handleHistory(exchange, store));
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1), "config-center-shutdown"));
        System.out.printf("CONFIG_CENTER_READY port=%d version=%d%n", port, store.get().version());
    }

    private static void handleConfig(HttpExchange exchange, DynamicConfigStore store) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 200, JsonUtil.mapToJson(store.get().toMap()));
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, JsonUtil.errorToJson("method not allowed"));
            return;
        }
        Map<String, String> params = QueryStringParser.parse(exchange.getRequestURI().getRawQuery());
        try {
            RuntimeConfigSnapshot updated = store.update(
                    parseLong(required(params, "expectedVersion"), "expectedVersion"),
                    parseInt(required(params, "newPipelinePercent"), "newPipelinePercent"),
                    parseInt(required(params, "shadowPercent"), "shadowPercent"),
                    DegradationLevel.parse(required(params, "degradationLevel")),
                    required(params, "updatedBy")
            );
            write(exchange, 200, JsonUtil.mapToJson(updated.toMap()));
        } catch (ConfigVersionConflictException e) {
            write(exchange, 409, JsonUtil.errorToJson(e.getMessage()));
        } catch (IllegalArgumentException e) {
            write(exchange, 400, JsonUtil.errorToJson(e.getMessage()));
        }
    }

    private static void handleHistory(HttpExchange exchange, DynamicConfigStore store) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, JsonUtil.errorToJson("method not allowed"));
            return;
        }
        List<Map<String, Object>> entries = store.history().stream().map(RuntimeConfigSnapshot::toMap).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", entries.size());
        body.put("entries", entries);
        write(exchange, 200, JsonUtil.mapToJson(body));
    }

    private static String required(Map<String, String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static int parseInt(String raw, String name) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private static long parseLong(String raw, String name) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
