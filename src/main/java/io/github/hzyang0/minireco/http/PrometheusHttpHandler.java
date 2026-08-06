package io.github.hzyang0.minireco.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.hzyang0.minireco.observability.MetricSample;
import io.github.hzyang0.minireco.observability.MetricsRegistry;
import io.github.hzyang0.minireco.service.data.JdbcDataRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

/** Exposes process metrics in the Prometheus text exposition format. */
public final class PrometheusHttpHandler implements HttpHandler {
    private final MetricsRegistry registry;
    private final JdbcDataRepository repository;

    public PrometheusHttpHandler(MetricsRegistry registry, JdbcDataRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        StringBuilder body = new StringBuilder();
        for (MetricSample sample : registry.samples()) {
            String name = sanitize(sample.getName());
            String labels = labels(sample.getTags());
            if (sample.getType() == MetricSample.Type.COUNTER) {
                body.append(name).append("_total").append(labels).append(' ')
                        .append(sample.getTotal()).append('\n');
            } else {
                body.append(name).append("_count").append(labels).append(' ')
                        .append(sample.getCount()).append('\n');
                body.append(name).append("_sum_ms").append(labels).append(' ')
                        .append(sample.getTotal()).append('\n');
                body.append(name).append("_max_ms").append(labels).append(' ')
                        .append(sample.getMax()).append('\n');
            }
        }
        JdbcDataRepository.PoolStats pool = repository.poolStats();
        gauge(body, "db_pool_connections", "state", "active", pool.active());
        gauge(body, "db_pool_connections", "state", "idle", pool.idle());
        gauge(body, "db_pool_connections", "state", "total", pool.total());
        gauge(body, "db_pool_pending_threads", null, null, pool.pending());

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void gauge(StringBuilder body, String name, String label, String value, long number) {
        body.append(name);
        if (label != null) {
            body.append('{').append(label).append("=\"").append(value).append("\"}");
        }
        body.append(' ').append(number).append('\n');
    }

    private String sanitize(String name) {
        return name.replace('.', '_').replace('-', '_');
    }

    private String labels(Map<String, String> tags) {
        if (tags.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(",", "{", "}");
        tags.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                joiner.add(sanitize(entry.getKey()) + "=\"" + escape(entry.getValue()) + "\"")
        );
        return joiner.toString();
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
