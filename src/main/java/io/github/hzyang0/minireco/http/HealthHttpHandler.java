package io.github.hzyang0.minireco.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.hzyang0.minireco.service.data.JdbcDataRepository;
import io.github.hzyang0.minireco.util.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Database-aware readiness endpoint. */
public final class HealthHttpHandler implements HttpHandler {
    private final JdbcDataRepository repository;

    public HealthHttpHandler(JdbcDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, Map.of("status", "DOWN", "error", "method not allowed"));
            return;
        }
        boolean databaseUp = repository.isHealthy();
        JdbcDataRepository.PoolStats pool = repository.poolStats();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", databaseUp ? "UP" : "DOWN");
        body.put("service", "mini-reco");
        body.put("database", databaseUp ? "UP" : "DOWN");
        body.put("pool", Map.of(
                "active", pool.active(),
                "idle", pool.idle(),
                "pending", pool.pending(),
                "total", pool.total()
        ));
        body.put("time", Instant.now().toString());
        write(exchange, databaseUp ? 200 : 503, body);
    }

    private void write(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
        byte[] bytes = JsonUtil.mapToJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
