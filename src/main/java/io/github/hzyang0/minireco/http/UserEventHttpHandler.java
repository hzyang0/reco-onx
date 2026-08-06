package io.github.hzyang0.minireco.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.hzyang0.minireco.service.data.JdbcDataRepository;
import io.github.hzyang0.minireco.observability.MetricsRegistry;
import io.github.hzyang0.minireco.util.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persists recommendation exposure and explicit user feedback. */
public final class UserEventHttpHandler implements HttpHandler {
    private static final int MAX_BODY_BYTES = 8 * 1024;
    private static final Set<String> EVENT_TYPES = Set.of("exposure", "view", "click", "cart", "purchase");
    private static final Set<String> SCENES = Set.of("mall", "video_feed", "buy_first");
    private final JdbcDataRepository repository;

    public UserEventHttpHandler(JdbcDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, JsonUtil.errorToJson("method not allowed"));
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("application/x-www-form-urlencoded")) {
            write(exchange, 415, JsonUtil.errorToJson("content type must be application/x-www-form-urlencoded"));
            return;
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            write(exchange, 413, JsonUtil.errorToJson("request body is too large"));
            return;
        }
        try {
            Map<String, String> form = QueryStringParser.parse(new String(bytes, StandardCharsets.UTF_8));
            long userId = positiveLong(form.get("userId"), "userId");
            if (repository.findUser(userId).isEmpty()) {
                write(exchange, 404, JsonUtil.errorToJson("user not found: " + userId));
                return;
            }
            String eventType = choice(form, "eventType", EVENT_TYPES);
            String scene = choice(form, "scene", SCENES);
            String requestId = text(form.get("requestId"), "requestId", 1, 64);
            List<Long> itemIds = parseItemIds(form.get("itemIds"));
            int inserted = repository.appendUserEvents(
                    userId, itemIds, eventType, requestId, scene, Instant.now().getEpochSecond()
            );
            MetricsRegistry.global().increment(
                    "user.event.accepted",
                    Map.of("eventType", eventType, "scene", scene)
            );
            write(exchange, 201, JsonUtil.mapToJson(Map.of(
                    "accepted", true,
                    "eventType", eventType,
                    "inserted", inserted,
                    "userId", userId
            )));
        } catch (IllegalArgumentException exception) {
            write(exchange, 400, JsonUtil.errorToJson(exception.getMessage()));
        } catch (IllegalStateException exception) {
            write(exchange, 503, JsonUtil.errorToJson("event storage unavailable"));
        }
    }

    private List<Long> parseItemIds(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("itemIds must not be blank");
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> positiveLong(value, "itemId"))
                .forEach(unique::add);
        if (unique.isEmpty() || unique.size() > 50) {
            throw new IllegalArgumentException("itemIds must contain between 1 and 50 unique values");
        }
        return List.copyOf(unique);
    }

    private long positiveLong(String raw, String name) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NullPointerException | NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
    }

    private String choice(Map<String, String> form, String name, Set<String> allowed) {
        String value = form.get(name);
        if (value == null || !allowed.contains(value)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private String text(String value, String name, int min, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < min || normalized.length() > max) {
            throw new IllegalArgumentException(name + " length must be between " + min + " and " + max);
        }
        return normalized;
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
