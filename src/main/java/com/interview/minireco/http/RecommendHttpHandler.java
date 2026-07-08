package com.interview.minireco.http;

import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.domain.RecommendResponse;
import com.interview.minireco.service.RecommendService;
import com.interview.minireco.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class RecommendHttpHandler implements HttpHandler {
    private final RecommendService recommendService;

    public RecommendHttpHandler(RecommendService recommendService) {
        this.recommendService = recommendService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, JsonUtil.errorToJson("method not allowed"));
            return;
        }

        try {
            Map<String, String> query = QueryStringParser.parse(exchange.getRequestURI().getRawQuery());
            long userId = Long.parseLong(query.getOrDefault("userId", "0"));
            String scene = query.getOrDefault("scene", "mall");
            int limit = Integer.parseInt(query.getOrDefault("limit", "10"));

            RecommendRequest request = new RecommendRequest(userId, scene, limit);
            RecommendResponse response = recommendService.recommend(request);
            writeJson(exchange, 200, JsonUtil.responseToJson(response));
        } catch (Exception e) {
            writeJson(exchange, 400, JsonUtil.errorToJson(e.getMessage()));
        }
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
