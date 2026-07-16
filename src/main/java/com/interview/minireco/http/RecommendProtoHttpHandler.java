package com.interview.minireco.http;

import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.domain.RecommendResponse;
import com.interview.minireco.proto.adapter.UpstreamRecommendProtoAdapter;
import com.interview.minireco.service.RecommendationFacade;
import com.interview.minireco.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class RecommendProtoHttpHandler implements HttpHandler {
    private final RecommendationFacade recommendService;

    public RecommendProtoHttpHandler(RecommendationFacade recommendService) {
        this.recommendService = recommendService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }

        try {
            Map<String, String> query = QueryStringParser.parse(exchange.getRequestURI().getRawQuery());
            long userId = Long.parseLong(query.getOrDefault("userId", "0"));
            String scene = query.getOrDefault("scene", "mall");
            int limit = Integer.parseInt(query.getOrDefault("limit", "10"));

            RecommendRequest request = new RecommendRequest(userId, scene, limit);
            RecommendResponse response = recommendService.recommend(request);
            byte[] body = UpstreamRecommendProtoAdapter.fromDomain(response).toByteArray();
            exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
            exchange.getResponseHeaders().add(
                    "X-Proto-Message",
                    "mini_reco.upstream.UpstreamRecommendResponsePb"
            );
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        } catch (Exception e) {
            writeError(exchange, 400, e.getMessage());
        }
    }

    private void writeError(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = JsonUtil.errorToJson(message).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
