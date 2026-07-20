package com.interview.minireco.security;

import com.interview.minireco.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SecuredAdminHttpHandler implements HttpHandler {
    private final HttpHandler delegate;
    private final AdminRequestAuthenticator authenticator;
    private final AdminPermission permission;

    public SecuredAdminHttpHandler(
            HttpHandler delegate,
            AdminRequestAuthenticator authenticator,
            AdminPermission permission
    ) {
        this.delegate = delegate;
        this.authenticator = authenticator;
        this.permission = permission;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            try {
                Map<String, String> headers = new LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((name, values) -> {
                    if (!values.isEmpty()) {
                        headers.put(name, values.get(0));
                    }
                });
                AdminPrincipal principal = authenticator.authenticate(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestURI().getRawQuery(),
                        headers,
                        permission
                );
                exchange.setAttribute("adminPrincipal", principal);
            } catch (AdminAuthException e) {
                write(exchange, e.status(), JsonUtil.errorToJson(e.getMessage()));
                return;
            }
        }
        delegate.handle(exchange);
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
