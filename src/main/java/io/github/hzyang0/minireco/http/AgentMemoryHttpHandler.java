package io.github.hzyang0.minireco.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.hzyang0.minireco.service.data.JdbcDataRepository;
import io.github.hzyang0.minireco.util.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Read-only memory inspection endpoint. A production version must authorize the user identity. */
public final class AgentMemoryHttpHandler implements HttpHandler {
    private final JdbcDataRepository repository;

    public AgentMemoryHttpHandler(JdbcDataRepository repository) { this.repository = repository; }

    @Override public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { write(exchange, 405, JsonUtil.errorToJson("method not allowed")); return; }
        try {
            long userId = Long.parseLong(QueryStringParser.parse(exchange.getRequestURI().getRawQuery()).get("userId"));
            if (userId <= 0) throw new NumberFormatException();
            if (repository.findUser(userId).isEmpty()) { write(exchange, 404, JsonUtil.errorToJson("user not found: " + userId)); return; }
            write(exchange, 200, JsonUtil.mapToJson(Map.of("userId", userId, "longTermMemory", repository.findAgentLongTermMemories(userId))));
        } catch (NullPointerException | NumberFormatException exception) {
            write(exchange, 400, JsonUtil.errorToJson("userId must be a positive integer"));
        } catch (IllegalStateException exception) {
            write(exchange, 503, JsonUtil.errorToJson("agent memory unavailable"));
        }
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
}
