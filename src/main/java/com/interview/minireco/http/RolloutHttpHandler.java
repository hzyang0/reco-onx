package com.interview.minireco.http;

import com.interview.minireco.migration.ComparisonRegistry;
import com.interview.minireco.migration.RolloutManager;
import com.interview.minireco.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class RolloutHttpHandler implements HttpHandler {
    private final RolloutManager rolloutManager;
    private final ComparisonRegistry comparisonRegistry;

    public RolloutHttpHandler(RolloutManager rolloutManager, ComparisonRegistry comparisonRegistry) {
        this.rolloutManager = rolloutManager;
        this.comparisonRegistry = comparisonRegistry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
            write(exchange, 405, JsonUtil.errorToJson("method not allowed"));
            return;
        }

        Map<String, String> params = QueryStringParser.parse(exchange.getRequestURI().getRawQuery());
        try {
            applyCommand(params);
        } catch (IllegalArgumentException e) {
            write(exchange, 400, JsonUtil.errorToJson(e.getMessage()));
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("config", rolloutManager.snapshot());
        body.put("comparison", comparisonRegistry.snapshot());
        body.put("usage", Map.of(
                "shadowCompare", "/rollout?newPercent=0&shadowPercent=100&clear=true",
                "canary5Percent", "/rollout?newPercent=5&shadowPercent=20",
                "fullRollout", "/rollout?newPercent=100&shadowPercent=0",
                "rollback", "/rollout?newPercent=0&shadowPercent=0"
        ));
        write(exchange, 200, JsonUtil.mapToJson(body));
    }

    private void applyCommand(Map<String, String> params) {
        if (Boolean.parseBoolean(params.getOrDefault("reset", "false"))) {
            rolloutManager.reset();
            comparisonRegistry.reset();
        }
        if (Boolean.parseBoolean(params.getOrDefault("clear", "false"))) {
            comparisonRegistry.reset();
        }
        Integer newPercent = params.containsKey("newPercent")
                ? parsePercent(params.get("newPercent"), "newPercent")
                : null;
        Integer shadowPercent = params.containsKey("shadowPercent")
                ? parsePercent(params.get("shadowPercent"), "shadowPercent")
                : null;
        rolloutManager.update(newPercent, shadowPercent);
    }

    private int parsePercent(String raw, String name) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
