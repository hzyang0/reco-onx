package io.github.hzyang0.minireco.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.hzyang0.minireco.util.JsonUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DashboardHttpHandler implements HttpHandler {
    private static final Map<String, AssetDefinition> ASSET_DEFINITIONS = Map.of(
            "/", new AssetDefinition("/dashboard/index.html", "text/html; charset=utf-8", "no-cache"),
            "/index.html", new AssetDefinition("/dashboard/index.html", "text/html; charset=utf-8", "no-cache"),
            "/favicon.svg", new AssetDefinition(
                    "/dashboard/favicon.svg",
                    "image/svg+xml",
                    "public, max-age=86400"
            ),
            "/assets/dashboard.css", new AssetDefinition(
                    "/dashboard/dashboard.css",
                    "text/css; charset=utf-8",
                    "public, max-age=3600"
            ),
            "/assets/dashboard.js", new AssetDefinition(
                    "/dashboard/dashboard.js",
                    "application/javascript; charset=utf-8",
                    "public, max-age=3600"
            )
    );

    private final Map<String, Asset> assets;

    public DashboardHttpHandler() {
        this.assets = loadAssets();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            writeJson(exchange, 405, JsonUtil.errorToJson("method not allowed"));
            return;
        }

        Asset asset = assets.get(exchange.getRequestURI().getPath());
        if (asset == null) {
            writeJson(exchange, 404, JsonUtil.errorToJson("resource not found"));
            return;
        }

        applySecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", asset.contentType());
        exchange.getResponseHeaders().set("Cache-Control", asset.cacheControl());
        if ("HEAD".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(200, asset.content().length);
        exchange.getResponseBody().write(asset.content());
        exchange.close();
    }

    private Map<String, Asset> loadAssets() {
        Map<String, Asset> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, AssetDefinition> entry : ASSET_DEFINITIONS.entrySet()) {
            AssetDefinition definition = entry.getValue();
            loaded.put(entry.getKey(), new Asset(
                    readResource(definition.resourcePath()),
                    definition.contentType(),
                    definition.cacheControl()
            ));
        }
        return Map.copyOf(loaded);
    }

    private byte[] readResource(String resourcePath) {
        try (InputStream input = DashboardHttpHandler.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("missing dashboard resource: " + resourcePath);
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to load dashboard resource: " + resourcePath, e);
        }
    }

    private void applySecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; "
                        + "img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
        );
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        applySecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, content.length);
        exchange.getResponseBody().write(content);
        exchange.close();
    }

    private record AssetDefinition(String resourcePath, String contentType, String cacheControl) {
    }

    private record Asset(byte[] content, String contentType, String cacheControl) {
        private Asset {
            content = content.clone();
        }
    }
}
