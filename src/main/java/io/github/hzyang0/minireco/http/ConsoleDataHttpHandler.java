package io.github.hzyang0.minireco.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.hzyang0.minireco.service.data.JdbcDataRepository;
import io.github.hzyang0.minireco.util.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Supplies database-backed user choices and catalog statistics to the console. */
public final class ConsoleDataHttpHandler implements HttpHandler {
    private final JdbcDataRepository repository;

    public ConsoleDataHttpHandler(JdbcDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, JsonUtil.errorToJson("method not allowed"));
            return;
        }

        try {
            List<Map<String, Object>> users = repository.findAllConsoleUsers().stream()
                    .map(this::toJsonMap)
                    .toList();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("users", users);
            response.put("userCount", users.size());
            response.put("catalogCount", repository.countCatalogItems());
            writeJson(exchange, 200, JsonUtil.mapToJson(response));
        } catch (RuntimeException exception) {
            writeJson(exchange, 503, JsonUtil.errorToJson("console data unavailable"));
        }
    }

    private Map<String, Object> toJsonMap(JdbcDataRepository.ConsoleUserProfile profile) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userId", profile.userId());
        user.put("personaName", profile.personaName());
        user.put("personaSummary", profile.personaSummary());
        user.put("age", profile.age());
        user.put("newUser", profile.newUser());
        user.put("preferredCategory", profile.preferredCategory());
        user.put("location", profile.province() + " · " + profile.city());
        user.put("defaultScene", profile.defaultScene());
        return user;
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
