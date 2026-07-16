package com.interview.minireco.capacity;

import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CapacityBenchmarkApplication {
    private CapacityBenchmarkApplication() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            throw new IllegalArgumentException(
                    "usage: <baseUrl> <warmupSeconds> <durationSeconds> <concurrencyCsv> <reportDirectory>"
            );
        }
        String baseUrl = args[0];
        int warmupSeconds = Integer.parseInt(args[1]);
        int durationSeconds = Integer.parseInt(args[2]);
        int[] levels = Arrays.stream(args[3].split(",")).mapToInt(Integer::parseInt).toArray();
        Path reportDirectory = Path.of(args[4]);
        double minSuccessRate = envDouble("SLO_MIN_SUCCESS_RATE", 99.0);
        double maxFallbackRate = envDouble("SLO_MAX_FALLBACK_RATE", 5.0);
        long maxP95Ms = (long) envDouble("SLO_MAX_P95_MS", 1200);
        int timeoutMs = (int) envDouble("LOAD_REQUEST_TIMEOUT_MS", 3000);

        Files.createDirectories(reportDirectory);
        HttpLoadGenerator generator = new HttpLoadGenerator(baseUrl, Duration.ofMillis(timeoutMs));
        List<LoadTestResult> results = new ArrayList<>();
        for (int concurrency : levels) {
            LoadTestResult result = generator.run(
                    concurrency, warmupSeconds, durationSeconds, minSuccessRate, maxFallbackRate, maxP95Ms
            );
            results.add(result);
            System.out.printf("LOAD_RESULT concurrency=%d qps=%.2f p95Ms=%d success=%.2f%% fallback=%.2f%% slo=%s%n",
                    concurrency, result.throughputQps(), result.p95Ms(), result.successRatePercent(),
                    result.fallbackRatePercent(), result.sloPass() ? "PASS" : "FAIL");
        }
        int maxPassingConcurrency = results.stream().filter(LoadTestResult::sloPass)
                .mapToInt(LoadTestResult::concurrency).max().orElse(0);
        writeReports(reportDirectory, baseUrl, minSuccessRate, maxFallbackRate, maxP95Ms,
                maxPassingConcurrency, results);
        System.out.printf("CAPACITY_REPORT_READY directory=%s maxPassingConcurrency=%d%n",
                reportDirectory.toAbsolutePath(), maxPassingConcurrency);
        if (maxPassingConcurrency == 0 && !Boolean.parseBoolean(System.getenv("ALLOW_SLO_FAILURE"))) {
            System.exit(2);
        }
    }

    private static void writeReports(Path directory, String baseUrl, double minSuccessRate,
                                     double maxFallbackRate, long maxP95Ms, int maxConcurrency,
                                     List<LoadTestResult> results) throws Exception {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generatedAt", Instant.now().toString());
        summary.put("target", baseUrl);
        summary.put("slo", Map.of(
                "minSuccessRatePercent", minSuccessRate,
                "maxFallbackRatePercent", maxFallbackRate,
                "maxP95Ms", maxP95Ms
        ));
        summary.put("maxPassingConcurrency", maxConcurrency);
        summary.put("results", results.stream().map(LoadTestResult::toMap).toList());
        Files.writeString(directory.resolve("summary.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(summary), StandardCharsets.UTF_8);

        StringBuilder csv = new StringBuilder("concurrency,requests,qps,success_rate,fallback_rate,p50_ms,p95_ms,p99_ms,max_ms,slo_pass\n");
        for (LoadTestResult result : results) {
            csv.append(result.concurrency()).append(',').append(result.requests()).append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.2f", result.throughputQps())).append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.2f", result.successRatePercent())).append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.2f", result.fallbackRatePercent())).append(',')
                    .append(result.p50Ms()).append(',').append(result.p95Ms()).append(',')
                    .append(result.p99Ms()).append(',').append(result.maxMs()).append(',')
                    .append(result.sloPass()).append('\n');
        }
        Files.writeString(directory.resolve("results.csv"), csv, StandardCharsets.UTF_8);

        StringBuilder markdown = new StringBuilder("# V20 Capacity Report\n\n")
                .append("SLO: success >= ").append(minSuccessRate).append("%, fallback <= ")
                .append(maxFallbackRate).append("%, P95 <= ").append(maxP95Ms).append("ms.\n\n")
                .append("Maximum passing concurrency: **").append(maxConcurrency).append("**\n\n")
                .append("| Concurrency | QPS | Success | Fallback | P50 | P95 | P99 | SLO |\n")
                .append("|---:|---:|---:|---:|---:|---:|---:|:---:|\n");
        for (LoadTestResult result : results) {
            markdown.append('|').append(result.concurrency()).append('|')
                    .append(String.format(java.util.Locale.ROOT, "%.2f", result.throughputQps())).append('|')
                    .append(String.format(java.util.Locale.ROOT, "%.2f%%", result.successRatePercent())).append('|')
                    .append(String.format(java.util.Locale.ROOT, "%.2f%%", result.fallbackRatePercent())).append('|')
                    .append(result.p50Ms()).append("ms|").append(result.p95Ms()).append("ms|")
                    .append(result.p99Ms()).append("ms|").append(result.sloPass() ? "PASS" : "FAIL").append("|\n");
        }
        Files.writeString(directory.resolve("report.md"), markdown, StandardCharsets.UTF_8);
    }

    private static double envDouble(String name, double defaultValue) {
        String raw = System.getenv(name);
        return raw == null || raw.isBlank() ? defaultValue : Double.parseDouble(raw);
    }
}
