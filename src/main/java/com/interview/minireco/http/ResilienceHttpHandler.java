package com.interview.minireco.http;

import com.interview.minireco.resilience.FaultInjectionManager;
import com.interview.minireco.resilience.FaultMode;
import com.interview.minireco.resilience.ResilienceRegistry;
import com.interview.minireco.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class ResilienceHttpHandler implements HttpHandler {
    private final ResilienceRegistry resilienceRegistry;
    private final FaultInjectionManager faultInjectionManager;

    public ResilienceHttpHandler(
            ResilienceRegistry resilienceRegistry,
            FaultInjectionManager faultInjectionManager
    ) {
        this.resilienceRegistry = resilienceRegistry;
        this.faultInjectionManager = faultInjectionManager;
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
        body.put("faultInjection", faultInjectionManager.snapshot());
        body.putAll(resilienceRegistry.snapshot());
        body.put("usage", Map.of(
                "view", "/resilience",
                "injectTimeout", "/resilience?source=live&fault=TIMEOUT",
                "injectError", "/resilience?source=live&fault=ERROR",
                "recover", "/resilience?reset=true"
        ));
        write(exchange, 200, JsonUtil.mapToJson(body));
    }

    private void applyCommand(Map<String, String> params) {
        if (Boolean.parseBoolean(params.getOrDefault("reset", "false"))) {
            faultInjectionManager.reset();
            resilienceRegistry.resetAll();
        }

        String source = params.get("source");
        String fault = params.get("fault");
        if (source == null && fault == null) {
            return;
        }
        if (source == null || source.isBlank() || fault == null || fault.isBlank()) {
            throw new IllegalArgumentException("source and fault must be provided together");
        }
        try {
            faultInjectionManager.set(source, FaultMode.parse(fault));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid resilience command: " + e.getMessage(), e);
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
