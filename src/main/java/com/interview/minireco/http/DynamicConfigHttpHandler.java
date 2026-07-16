package com.interview.minireco.http;

import com.interview.minireco.config.DynamicConfigPoller;
import com.interview.minireco.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class DynamicConfigHttpHandler implements HttpHandler {
    private final DynamicConfigPoller poller;

    public DynamicConfigHttpHandler(DynamicConfigPoller poller) {
        this.poller = poller;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, JsonUtil.errorToJson("method not allowed"));
            return;
        }
        write(exchange, 200, JsonUtil.mapToJson(poller.snapshot()));
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
