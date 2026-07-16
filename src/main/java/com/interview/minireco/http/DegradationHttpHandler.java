package com.interview.minireco.http;

import com.interview.minireco.degradation.DegradationLevel;
import com.interview.minireco.degradation.DegradationManager;
import com.interview.minireco.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class DegradationHttpHandler implements HttpHandler {
    private final DegradationManager degradationManager;

    public DegradationHttpHandler(DegradationManager degradationManager) {
        this.degradationManager = degradationManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
            write(exchange, 405, JsonUtil.errorToJson("method not allowed"));
            return;
        }

        Map<String, String> params = QueryStringParser.parse(exchange.getRequestURI().getRawQuery());
        String requestedLevel = params.get("level");
        if (requestedLevel != null && !requestedLevel.isBlank()) {
            try {
                degradationManager.setLevel(DegradationLevel.parse(requestedLevel));
            } catch (IllegalArgumentException e) {
                write(exchange, 400, JsonUtil.errorToJson("invalid degradation level: " + requestedLevel));
                return;
            }
        }

        Map<String, Object> body = new LinkedHashMap<>(degradationManager.snapshot());
        body.put("usage", Map.of(
                "view", "/degradation",
                "setLight", "/degradation?level=LIGHT",
                "setHeavy", "/degradation?level=HEAVY",
                "disable", "/degradation?level=NONE"
        ));
        write(exchange, 200, JsonUtil.mapToJson(body));
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
