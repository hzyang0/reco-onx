package io.github.hzyang0.minireco.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.hzyang0.minireco.service.data.JdbcDataRepository;
import io.github.hzyang0.minireco.util.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Creates a database-backed user profile from the console form. */
public final class UserProfileHttpHandler implements HttpHandler {
    private static final int MAX_BODY_BYTES = 8 * 1024;
    private static final Set<String> CATEGORIES = Set.of("home", "digital", "food", "fashion", "sports");
    private static final Set<String> SCENES = Set.of("mall", "video_feed", "buy_first");
    private static final Set<String> RANK_EXPERIMENTS = Set.of("DEFAULT", "MALL_BOOST");
    private final JdbcDataRepository repository;

    public UserProfileHttpHandler(JdbcDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, JsonUtil.errorToJson("method not allowed"));
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("application/x-www-form-urlencoded")) {
            writeJson(exchange, 415, JsonUtil.errorToJson("content type must be application/x-www-form-urlencoded"));
            return;
        }

        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            writeJson(exchange, 413, JsonUtil.errorToJson("request body is too large"));
            return;
        }

        try {
            Map<String, String> form = QueryStringParser.parse(new String(body, StandardCharsets.UTF_8));
            long userId = parseUserId(form.get("userId"));
            if (repository.findUser(userId).isPresent()) {
                writeJson(exchange, 409, JsonUtil.errorToJson("userId already exists"));
                return;
            }

            int age = parseAge(form.get("age"));
            String category = choice(form, "category", CATEGORIES);
            String scene = choice(form, "scene", SCENES);
            String rankExperiment = choice(form, "rankExperiment", RANK_EXPERIMENTS);
            String behaviorLevel = choice(
                    form,
                    "behaviorLevel",
                    Set.of("cold_start", "interested", "high_intent")
            );
            String personaName = text(form, "personaName", 2, 20);
            String personaSummary = text(form, "personaSummary", 4, 80);
            String province = text(form, "province", 2, 20);
            String city = text(form, "city", 2, 20);

            JdbcDataRepository.ConsoleUserProfile profile = new JdbcDataRepository.ConsoleUserProfile(
                    userId,
                    age,
                    "cold_start".equals(behaviorLevel),
                    category,
                    province,
                    city,
                    personaName,
                    personaSummary,
                    scene
            );
            repository.createConsoleUser(profile, eventsFor(behaviorLevel), rankExperiment);
            writeJson(exchange, 201, JsonUtil.mapToJson(Map.of(
                    "created", true,
                    "userId", userId,
                    "behaviorLevel", behaviorLevel
            )));
        } catch (IllegalArgumentException exception) {
            writeJson(exchange, 400, JsonUtil.errorToJson(exception.getMessage()));
        } catch (RuntimeException exception) {
            writeJson(exchange, 503, JsonUtil.errorToJson("failed to create user profile"));
        }
    }

    private long parseUserId(String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NullPointerException | NumberFormatException exception) {
            throw new IllegalArgumentException("userId must be a positive integer");
        }
    }

    private int parseAge(String raw) {
        try {
            int value = Integer.parseInt(raw);
            if (value < 12 || value > 100) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NullPointerException | NumberFormatException exception) {
            throw new IllegalArgumentException("age must be between 12 and 100");
        }
    }

    private String choice(Map<String, String> form, String name, Set<String> allowed) {
        String value = form.get(name);
        if (value == null || !allowed.contains(value)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private String text(Map<String, String> form, String name, int minLength, int maxLength) {
        String value = form.getOrDefault(name, "").trim();
        if (value.length() < minLength || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " length must be between "
                    + minLength + " and " + maxLength);
        }
        return value;
    }

    private List<String> eventsFor(String behaviorLevel) {
        return switch (behaviorLevel) {
            case "interested" -> List.of("view", "click");
            case "high_intent" -> List.of("view", "click", "cart", "purchase");
            default -> List.of();
        };
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
