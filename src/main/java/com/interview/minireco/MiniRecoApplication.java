package com.interview.minireco;

import com.interview.minireco.config.DynamicConfigPoller;
import com.interview.minireco.config.HttpConfigFetcher;
import com.interview.minireco.degradation.DegradationManager;
import com.interview.minireco.grpc.client.RecallTransportFactory;
import com.interview.minireco.http.DegradationHttpHandler;
import com.interview.minireco.http.DynamicConfigHttpHandler;
import com.interview.minireco.http.FeatureCacheHttpHandler;
import com.interview.minireco.http.RecommendHttpHandler;
import com.interview.minireco.http.RecommendProtoHttpHandler;
import com.interview.minireco.http.ResilienceHttpHandler;
import com.interview.minireco.http.RolloutHttpHandler;
import com.interview.minireco.migration.ComparisonRegistry;
import com.interview.minireco.migration.RolloutManager;
import com.interview.minireco.observability.AlertManager;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.observability.PrometheusMetricsHandler;
import com.interview.minireco.proto.ProtoRuntimeWarmup;
import com.interview.minireco.resilience.FaultInjectionManager;
import com.interview.minireco.resilience.ResilienceRegistry;
import com.interview.minireco.service.DemoWiring;
import com.interview.minireco.service.RecommendationFacade;
import com.interview.minireco.telemetry.Telemetry;
import com.interview.minireco.telemetry.TracingHttpHandler;
import com.interview.minireco.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;

public class MiniRecoApplication {
    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);
        Telemetry.initialize("mini-reco-gateway");
        ProtoRuntimeWarmup.initialize();
        RecommendationFacade recommendService = DemoWiring.createRoutedRecommendService();
        MetricsRegistry metricsRegistry = MetricsRegistry.global();
        AlertManager alertManager = new AlertManager(metricsRegistry);
        DegradationManager degradationManager = DegradationManager.global();
        ResilienceRegistry resilienceRegistry = ResilienceRegistry.global();
        FaultInjectionManager faultInjectionManager = FaultInjectionManager.global();
        RolloutManager rolloutManager = RolloutManager.global();
        ComparisonRegistry comparisonRegistry = ComparisonRegistry.global();
        DynamicConfigPoller configPoller = createConfigPoller(rolloutManager, degradationManager);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(
                "/recommend",
                new TracingHttpHandler("GET /recommend", new RecommendHttpHandler(recommendService))
        );
        server.createContext(
                "/recommend-pb",
                new TracingHttpHandler("GET /recommend-pb", new RecommendProtoHttpHandler(recommendService))
        );
        server.createContext("/health", exchange -> {
            String body = JsonUtil.mapToJson(Map.of(
                    "status", "UP",
                    "service", "mini-reco-access-layer",
                    "recallTransport", RecallTransportFactory.snapshot(),
                    "time", Instant.now().toString()
            ));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/metrics", exchange ->
                writeJson(exchange, JsonUtil.mapToJson(metricsRegistry.snapshot()))
        );
        server.createContext("/metrics/prometheus", new PrometheusMetricsHandler(metricsRegistry));
        server.createContext("/alerts", exchange ->
                writeJson(exchange, JsonUtil.mapToJson(alertManager.snapshot()))
        );
        server.createContext("/degradation", new DegradationHttpHandler(degradationManager));
        server.createContext(
                "/resilience",
                new ResilienceHttpHandler(resilienceRegistry, faultInjectionManager)
        );
        server.createContext("/rollout", new RolloutHttpHandler(rolloutManager, comparisonRegistry));
        server.createContext("/feature-cache", new FeatureCacheHttpHandler());
        if (configPoller != null) {
            server.createContext("/runtime-config", new DynamicConfigHttpHandler(configPoller));
        }
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        System.out.printf("MiniReco service started on port %d%n", port);
        System.out.printf("Recall transport: %s%n", RecallTransportFactory.snapshot());
        System.out.printf("Try: http://localhost:%d/recommend?userId=123&scene=mall&limit=10%n", port);
        System.out.printf("Protobuf: http://localhost:%d/recommend-pb?userId=123&scene=mall&limit=10%n", port);
        System.out.printf("Metrics: http://localhost:%d/metrics%n", port);
        System.out.printf("Prometheus: http://localhost:%d/metrics/prometheus%n", port);
        System.out.printf("Alerts: http://localhost:%d/alerts%n", port);
        System.out.printf("Degradation: http://localhost:%d/degradation%n", port);
        System.out.printf("Resilience: http://localhost:%d/resilience%n", port);
        System.out.printf("Rollout: http://localhost:%d/rollout%n", port);
        System.out.printf("Feature cache: http://localhost:%d/feature-cache%n", port);
        if (configPoller != null) {
            System.out.printf("Runtime config: http://localhost:%d/runtime-config%n", port);
        }
    }

    private static DynamicConfigPoller createConfigPoller(
            RolloutManager rolloutManager,
            DegradationManager degradationManager
    ) {
        String url = System.getenv("CONFIG_CENTER_URL");
        if (url == null || url.isBlank()) {
            return null;
        }
        long intervalMs = parsePositiveLong("CONFIG_POLL_INTERVAL_MS", 500);
        long staleAfterMs = parsePositiveLong("CONFIG_STALE_AFTER_MS", Math.max(3000, intervalMs * 5));
        DynamicConfigPoller poller = new DynamicConfigPoller(
                new HttpConfigFetcher(url), rolloutManager, degradationManager, staleAfterMs
        );
        poller.start(intervalMs);
        Runtime.getRuntime().addShutdownHook(new Thread(poller::close, "dynamic-config-shutdown"));
        return poller;
    }

    private static long parsePositiveLong(String name, long defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        long value = Long.parseLong(raw);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int resolvePort(String[] args) {
        if (args.length > 0) {
            return Integer.parseInt(args[0]);
        }
        String envPort = System.getenv("PORT");
        if (envPort == null || envPort.isBlank()) {
            return 8080;
        }
        return Integer.parseInt(envPort);
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            body = JsonUtil.errorToJson("method not allowed");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(405, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
