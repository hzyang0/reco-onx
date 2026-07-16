package com.interview.minireco.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Telemetry {
    private static final String INSTRUMENTATION_NAME = "com.interview.minireco";
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private Telemetry() {
    }

    public static void initialize(String defaultServiceName) {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("otel.service.name", defaultServiceName);
        defaults.put("otel.sdk.disabled", configuredSdkDisabled());
        defaults.put("otel.metrics.exporter", "none");
        defaults.put("otel.logs.exporter", "none");
        AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> defaults)
                .setResultAsGlobal()
                .build();
        System.out.printf(
                "OpenTelemetry initialized service=%s disabled=%s%n",
                configuredServiceName(defaultServiceName),
                configuredSdkDisabled()
        );
    }

    public static Tracer tracer() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    private static String configuredSdkDisabled() {
        String systemValue = System.getProperty("otel.sdk.disabled");
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue.trim();
        }
        String envValue = System.getenv("OTEL_SDK_DISABLED");
        return envValue == null || envValue.isBlank() ? "true" : envValue.trim();
    }

    private static String configuredServiceName(String defaultServiceName) {
        String systemValue = System.getProperty("otel.service.name");
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue.trim();
        }
        String envValue = System.getenv("OTEL_SERVICE_NAME");
        return envValue == null || envValue.isBlank() ? defaultServiceName : envValue.trim();
    }
}
