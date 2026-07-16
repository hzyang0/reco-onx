package com.interview.minireco.http;

import com.interview.minireco.cache.FeatureCacheRuntime;
import com.interview.minireco.cache.FeatureCacheStats;
import com.interview.minireco.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class FeatureCacheHttpHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                && !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, JsonUtil.errorToJson("method not allowed"));
            return;
        }
        Map<String, String> params = QueryStringParser.parse(exchange.getRequestURI().getRawQuery());
        if (Boolean.parseBoolean(params.getOrDefault("reset", "false"))) {
            FeatureCacheStats.global().reset();
        }
        write(exchange, 200, JsonUtil.mapToJson(
                FeatureCacheStats.global().snapshot(FeatureCacheRuntime.isEnabled())
        ));
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
