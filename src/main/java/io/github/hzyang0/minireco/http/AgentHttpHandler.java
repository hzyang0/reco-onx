package io.github.hzyang0.minireco.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.hzyang0.minireco.agent.RecommendationAgentService;
import io.github.hzyang0.minireco.util.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** HTTP boundary for the conversational recommendation Agent and the diagnosis Agent. */
public final class AgentHttpHandler implements HttpHandler {
    private static final int MAX_BODY_BYTES = 8 * 1024;
    private final RecommendationAgentService agentService;
    private final boolean diagnosis;

    public AgentHttpHandler(RecommendationAgentService agentService, boolean diagnosis) {
        this.agentService = agentService;
        this.diagnosis = diagnosis;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> input = readInput(exchange);
            long userId = positiveLong(input.get("userId"));
            Map<String, Object> result = diagnosis
                    ? agentService.diagnose(userId, input.get("scene"))
                    : agentService.chat(userId, input.get("sessionId"), requiredMessage(input.get("message")));
            write(exchange, 200, JsonUtil.mapToJson(result));
        } catch (IllegalArgumentException exception) {
            write(exchange, 400, JsonUtil.errorToJson(exception.getMessage()));
        } catch (IllegalStateException exception) {
            write(exchange, 503, JsonUtil.errorToJson("agent dependency unavailable"));
        }
    }

    private Map<String, String> readInput(HttpExchange exchange) throws IOException {
        if (diagnosis) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                throw new IllegalArgumentException("diagnosis endpoint only supports GET");
            }
            return QueryStringParser.parse(exchange.getRequestURI().getRawQuery());
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            throw new IllegalArgumentException("agent chat endpoint only supports POST");
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("application/x-www-form-urlencoded")) {
            throw new IllegalArgumentException("content type must be application/x-www-form-urlencoded");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("request body is too large");
        return QueryStringParser.parse(new String(bytes, StandardCharsets.UTF_8));
    }

    private long positiveLong(String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NullPointerException | NumberFormatException exception) {
            throw new IllegalArgumentException("userId must be a positive integer");
        }
    }

    private String requiredMessage(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 1000) {
            throw new IllegalArgumentException("message length must be between 1 and 1000");
        }
        return raw.trim();
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
