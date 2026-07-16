package com.interview.minireco.capacity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class HttpLoadGenerator {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final String baseUrl;
    private final Duration requestTimeout;
    private final AtomicLong sequence = new AtomicLong();

    public HttpLoadGenerator(String baseUrl, Duration requestTimeout) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.requestTimeout = requestTimeout;
    }

    public LoadTestResult run(int concurrency, int warmupSeconds, int durationSeconds,
                              double minSuccessRate, double maxFallbackRate, long maxP95Ms) throws Exception {
        if (warmupSeconds > 0) { runPhase(concurrency, warmupSeconds, false); }
        long started = System.nanoTime();
        List<LoadResultCalculator.Sample> samples = runPhase(concurrency, durationSeconds, true);
        double actualDuration = Math.max(0.001, (System.nanoTime() - started) / 1_000_000_000.0);
        return LoadResultCalculator.calculate(
                concurrency, samples, actualDuration, minSuccessRate, maxFallbackRate, maxP95Ms
        );
    }

    private List<LoadResultCalculator.Sample> runPhase(int concurrency, int seconds, boolean collect) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<List<LoadResultCalculator.Sample>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(worker(deadline, collect)));
            }
            List<LoadResultCalculator.Sample> combined = new ArrayList<>();
            for (Future<List<LoadResultCalculator.Sample>> future : futures) {
                combined.addAll(future.get(seconds + 10L, TimeUnit.SECONDS));
            }
            return combined;
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<List<LoadResultCalculator.Sample>> worker(long deadline, boolean collect) {
        return () -> {
            List<LoadResultCalculator.Sample> samples = new ArrayList<>();
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                long id = sequence.incrementAndGet();
                long userId = 10_000 + Math.floorMod(id, 100);
                long started = System.nanoTime();
                boolean success = false;
                boolean fallback = false;
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(
                                    baseUrl + "/recommend?userId=" + userId + "&scene=mall&limit=10"))
                            .timeout(requestTimeout).GET().build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                        int returned = root.getAsJsonObject("debug").get("returnedItemCount").getAsInt();
                        success = returned == 10;
                        fallback = response.body().contains("\"status\":\"FALLBACK\"");
                    }
                } catch (Exception ignored) {
                    // A failed or timed-out sample is part of the result, not a reason to abort the load test.
                }
                if (collect) {
                    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    samples.add(new LoadResultCalculator.Sample(latencyMs, success, fallback));
                }
            }
            return samples;
        };
    }
}
