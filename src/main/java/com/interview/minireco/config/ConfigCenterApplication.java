package com.interview.minireco.config;

import com.interview.minireco.degradation.DegradationLevel;
import com.interview.minireco.http.QueryStringParser;
import com.interview.minireco.security.AdminAuthConfig;
import com.interview.minireco.security.AdminPermission;
import com.interview.minireco.security.AdminPrincipal;
import com.interview.minireco.security.AdminRequestAuthenticator;
import com.interview.minireco.security.SecuredAdminHttpHandler;
import com.interview.minireco.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class ConfigCenterApplication {
    private ConfigCenterApplication() {
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8888;
        DynamicConfigStore store = createStore();
        AdminAuthConfig adminAuthConfig = AdminAuthConfig.fromEnvironment();
        AdminRequestAuthenticator adminAuthenticator = new AdminRequestAuthenticator(adminAuthConfig);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", exchange -> write(exchange, 200, JsonUtil.mapToJson(Map.of(
                "status", "UP",
                "service", "mini-reco-config-center",
                "version", store.get().version(),
                "storage", store.storageDescription(),
                "adminAuthEnabled", adminAuthConfig.enabled()
        ))));
        server.createContext("/api/config", new SecuredAdminHttpHandler(
                exchange -> handleConfig(exchange, store), adminAuthenticator, AdminPermission.CONFIG_WRITE
        ));
        server.createContext("/api/config/history", exchange -> handleHistory(exchange, store));
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1), "config-center-shutdown"));
        System.out.printf("CONFIG_CENTER_READY port=%d version=%d storage=%s%n",
                port, store.get().version(), store.storageDescription());
    }

    private static DynamicConfigStore createStore() {
        String path = System.getenv("CONFIG_STORE_PATH");
        ConfigJournal journal = path == null || path.isBlank()
                ? new InMemoryConfigJournal()
                : new FileConfigJournal(Path.of(path));
        return new DynamicConfigStore(java.time.Clock.systemUTC(), journal);
    }

    private static void handleConfig(HttpExchange exchange, DynamicConfigStore store) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 200, JsonUtil.mapToJson(store.get().toMap()));
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, JsonUtil.errorToJson("method not allowed"));
            return;
        }
        Map<String, String> params = QueryStringParser.parse(exchange.getRequestURI().getRawQuery());
        try {
            AdminPrincipal principal = (AdminPrincipal) exchange.getAttribute("adminPrincipal");
            String updatedBy = principal != null && !"LOCAL".equals(principal.role())
                    ? principal.keyId()
                    : required(params, "updatedBy");
            RuntimeConfigSnapshot updated = store.update(
                    parseLong(required(params, "expectedVersion"), "expectedVersion"),
                    parseInt(required(params, "newPipelinePercent"), "newPipelinePercent"),
                    parseInt(required(params, "shadowPercent"), "shadowPercent"),
                    DegradationLevel.parse(required(params, "degradationLevel")),
                    updatedBy
            );
            write(exchange, 200, JsonUtil.mapToJson(updated.toMap()));
        } catch (ConfigVersionConflictException e) {
            write(exchange, 409, JsonUtil.errorToJson(e.getMessage()));
        } catch (ConfigPersistenceException e) {
            write(exchange, 503, JsonUtil.errorToJson(e.getMessage()));
        } catch (IllegalArgumentException e) {
            write(exchange, 400, JsonUtil.errorToJson(e.getMessage()));
        }
    }

    private static void handleHistory(HttpExchange exchange, DynamicConfigStore store) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, JsonUtil.errorToJson("method not allowed"));
            return;
        }
        List<Map<String, Object>> entries = store.history().stream().map(RuntimeConfigSnapshot::toMap).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", entries.size());
        body.put("entries", entries);
        write(exchange, 200, JsonUtil.mapToJson(body));
    }

    private static String required(Map<String, String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static int parseInt(String raw, String name) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private static long parseLong(String raw, String name) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
