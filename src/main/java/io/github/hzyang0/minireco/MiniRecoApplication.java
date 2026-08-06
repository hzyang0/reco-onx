package io.github.hzyang0.minireco;

import io.github.hzyang0.minireco.http.DashboardHttpHandler;
import io.github.hzyang0.minireco.http.ConsoleDataHttpHandler;
import io.github.hzyang0.minireco.http.HealthHttpHandler;
import io.github.hzyang0.minireco.http.PrometheusHttpHandler;
import io.github.hzyang0.minireco.http.RecommendHttpHandler;
import io.github.hzyang0.minireco.http.UserEventHttpHandler;
import io.github.hzyang0.minireco.http.UserProfileHttpHandler;
import io.github.hzyang0.minireco.observability.MetricsRegistry;
import io.github.hzyang0.minireco.service.ApplicationWiring;
import io.github.hzyang0.minireco.service.RecommendationFacade;
import io.github.hzyang0.minireco.service.data.JdbcDataRepository;
import io.github.hzyang0.minireco.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class MiniRecoApplication {
    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);
        JdbcDataRepository repository = ApplicationWiring.createRepository();
        RecommendationFacade recommendationFacade = ApplicationWiring.createRecommendService(repository);
        MetricsRegistry metricsRegistry = MetricsRegistry.global();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/recommend", new RecommendHttpHandler(recommendationFacade, metricsRegistry));
        server.createContext("/live", exchange -> writeJson(exchange, JsonUtil.mapToJson(Map.of(
                "status", "UP",
                "service", "mini-reco",
                "time", Instant.now().toString()
        ))));
        server.createContext("/health", new HealthHttpHandler(repository));
        server.createContext("/metrics", exchange ->
                writeJson(exchange, JsonUtil.mapToJson(metricsRegistry.snapshot()))
        );
        server.createContext("/api/console-data", new ConsoleDataHttpHandler(repository));
        server.createContext("/api/users", new UserProfileHttpHandler(repository));
        server.createContext("/api/events", new UserEventHttpHandler(repository));
        server.createContext("/metrics/prometheus", new PrometheusHttpHandler(metricsRegistry, repository));
        server.createContext("/", new DashboardHttpHandler());
        ExecutorService httpExecutor = Executors.newFixedThreadPool(16);
        server.setExecutor(httpExecutor);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(2);
            httpExecutor.shutdownNow();
            recommendationFacade.close();
            repository.close();
        }, "mini-reco-shutdown"));

        System.out.printf("Mini Reco started on port %d%n", port);
        System.out.printf("Console: http://localhost:%d/%n", port);
        System.out.printf("Recommend: http://localhost:%d/recommend?userId=123&scene=mall&limit=10%n", port);
        System.out.printf("Health: http://localhost:%d/health%n", port);
        System.out.printf("Metrics: http://localhost:%d/metrics%n", port);
        System.out.printf("Prometheus: http://localhost:%d/metrics/prometheus%n", port);
    }

    private static int resolvePort(String[] args) {
        if (args.length == 0) {
            return DEFAULT_PORT;
        }
        int port = Integer.parseInt(args[0]);
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        return port;
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
