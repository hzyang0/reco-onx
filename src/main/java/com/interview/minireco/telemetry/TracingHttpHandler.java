package com.interview.minireco.telemetry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.io.IOException;

public final class TracingHttpHandler implements HttpHandler {
    private static final TextMapGetter<HttpExchange> HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpExchange carrier) {
            return carrier.getRequestHeaders().keySet();
        }

        @Override
        public String get(HttpExchange carrier, String key) {
            return carrier == null ? null : carrier.getRequestHeaders().getFirst(key);
        }
    };

    private final String spanName;
    private final HttpHandler delegate;

    public TracingHttpHandler(String spanName, HttpHandler delegate) {
        this.spanName = spanName;
        this.delegate = delegate;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Context parent = io.opentelemetry.api.GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), exchange, HEADER_GETTER);
        Span span = Telemetry.tracer().spanBuilder(spanName)
                .setParent(parent)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        span.setAttribute("http.request.method", exchange.getRequestMethod());
        span.setAttribute("url.path", exchange.getRequestURI().getPath());
        exchange.getResponseHeaders().add("X-Trace-Id", span.getSpanContext().getTraceId());
        try (Scope ignored = span.makeCurrent()) {
            delegate.handle(exchange);
            int statusCode = exchange.getResponseCode();
            if (statusCode > 0) {
                span.setAttribute("http.response.status_code", statusCode);
                if (statusCode >= 500) {
                    span.setStatus(StatusCode.ERROR);
                }
            }
        } catch (IOException | RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage() == null ? "request failed" : e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }
}
