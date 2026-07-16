package com.interview.minireco.observability;

import com.interview.minireco.util.JsonUtil;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public class StructuredLogger {
    private static final LogLevel MIN_LEVEL = resolveMinLevel();

    private final String loggerName;

    private StructuredLogger(Class<?> type) {
        this.loggerName = type.getSimpleName();
    }

    public static StructuredLogger getLogger(Class<?> type) {
        return new StructuredLogger(type);
    }

    public void debug(String requestId, String event, Supplier<Map<String, Object>> fieldsSupplier) {
        log(LogLevel.DEBUG, requestId, event, fieldsSupplier, null);
    }

    public void info(String requestId, String event, Supplier<Map<String, Object>> fieldsSupplier) {
        log(LogLevel.INFO, requestId, event, fieldsSupplier, null);
    }

    public void warn(String requestId, String event, Supplier<Map<String, Object>> fieldsSupplier) {
        log(LogLevel.WARN, requestId, event, fieldsSupplier, null);
    }

    public void error(String requestId, String event, Supplier<Map<String, Object>> fieldsSupplier, Throwable error) {
        log(LogLevel.ERROR, requestId, event, fieldsSupplier, error);
    }

    private void log(
            LogLevel level,
            String requestId,
            String event,
            Supplier<Map<String, Object>> fieldsSupplier,
            Throwable error
    ) {
        if (!enabled(level)) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("time", Instant.now().toString());
        payload.put("level", level.name());
        payload.put("logger", loggerName);
        payload.put("event", event);
        if (requestId != null) {
            payload.put("requestId", requestId);
        }
        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            payload.put("traceId", spanContext.getTraceId());
            payload.put("spanId", spanContext.getSpanId());
        }
        if (fieldsSupplier != null) {
            payload.putAll(fieldsSupplier.get());
        }
        if (error != null) {
            payload.put("errorType", error.getClass().getSimpleName());
            payload.put("errorMessage", error.getMessage());
        }

        String line = JsonUtil.mapToJson(payload);
        if (level == LogLevel.ERROR || level == LogLevel.WARN) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
    }

    private boolean enabled(LogLevel level) {
        return level.ordinal() >= MIN_LEVEL.ordinal();
    }

    private static LogLevel resolveMinLevel() {
        String configured = System.getenv("LOG_LEVEL");
        if (configured == null || configured.isBlank()) {
            return LogLevel.INFO;
        }
        try {
            return LogLevel.valueOf(configured.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return LogLevel.INFO;
        }
    }
}
