package com.interview.minireco.benchmark;

import com.interview.minireco.degradation.DegradationLevel;
import com.interview.minireco.degradation.DegradationManager;
import com.interview.minireco.degradation.UserLayer;
import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.domain.RecommendResponse;
import com.interview.minireco.service.DemoWiring;
import com.interview.minireco.service.RecommendService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A repeatable local experiment that compares NONE, LIGHT and HEAVY degradation.
 *
 * <p>This is deliberately separate from JUnit: unit tests verify correctness,
 * while this runner collects latency and effect data without making CI depend
 * on unstable timing assertions.</p>
 */
public final class DegradationBenchmark {
    private static final int DEFAULT_ITERATIONS = 5;
    private static final Path DEFAULT_OUTPUT = Path.of("target", "degradation-benchmark.csv");
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("protected", 123L),
            new Scenario("heavy_only", 160L),
            new Scenario("light_and_heavy", 185L)
    );

    private DegradationBenchmark() {
    }

    public static void main(String[] args) throws IOException {
        int iterations = parseIterations(args);
        Path output = args.length >= 2 ? Path.of(args[1]) : DEFAULT_OUTPUT;
        RecommendService recommendService = DemoWiring.createRecommendService();
        DegradationManager degradationManager = DegradationManager.global();

        List<RunSample> samples = new ArrayList<>();
        try {
            warmUp(recommendService, degradationManager);
            for (DegradationLevel level : DegradationLevel.values()) {
                degradationManager.setLevel(level);
                for (Scenario scenario : SCENARIOS) {
                    for (int i = 0; i < iterations; i++) {
                        samples.add(runOnce(recommendService, level, scenario));
                    }
                }
            }
        } finally {
            degradationManager.setLevel(DegradationLevel.NONE);
        }

        List<BenchmarkResult> results = summarize(samples, iterations);
        printReport(results, output);
        writeCsv(results, output);
    }

    static int parseIterations(String[] args) {
        if (args.length == 0) {
            return DEFAULT_ITERATIONS;
        }
        try {
            int iterations = Integer.parseInt(args[0]);
            if (iterations <= 0 || iterations > 100) {
                throw new IllegalArgumentException("iterations must be between 1 and 100");
            }
            return iterations;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("iterations must be an integer", e);
        }
    }

    static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        if (percentile <= 0 || percentile > 1) {
            throw new IllegalArgumentException("percentile must be in (0, 1]");
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index);
    }

    private static void warmUp(RecommendService service, DegradationManager manager) {
        manager.setLevel(DegradationLevel.NONE);
        service.recommend(new RecommendRequest(SCENARIOS.get(0).userId(), "mall", 10));
    }

    private static RunSample runOnce(RecommendService service, DegradationLevel level, Scenario scenario) {
        RecommendResponse response = service.recommend(new RecommendRequest(scenario.userId(), "mall", 10));
        Map<String, Object> debug = response.getDebug();
        Map<String, Object> degradation = mapValue(debug.get("degradation"));

        return new RunSample(
                level,
                scenario,
                response.getCostMs(),
                intValue(debug, "recallItemCount"),
                intValue(debug, "returnedItemCount"),
                Boolean.TRUE.equals(degradation.get("degraded")),
                numberValue(degradation, "effectiveLimit"),
                stringList(degradation.get("skippedRecallSources"))
        );
    }

    private static List<BenchmarkResult> summarize(List<RunSample> samples, int iterations) {
        return samples.stream()
                .collect(Collectors.groupingBy(
                        sample -> new ResultKey(sample.level(), sample.scenario()),
                        java.util.LinkedHashMap::new,
                        Collectors.toList()
                ))
                .entrySet()
                .stream()
                .map(entry -> aggregate(entry.getKey(), entry.getValue(), iterations))
                .sorted(Comparator
                        .comparing((BenchmarkResult result) -> result.level().ordinal())
                        .thenComparingLong(BenchmarkResult::userId))
                .toList();
    }

    private static BenchmarkResult aggregate(ResultKey key, List<RunSample> samples, int iterations) {
        List<Long> costs = samples.stream().map(RunSample::costMs).toList();
        double averageCost = costs.stream().mapToLong(Long::longValue).average().orElse(0);
        double averageRecall = samples.stream().mapToInt(RunSample::recallItemCount).average().orElse(0);
        double averageReturned = samples.stream().mapToInt(RunSample::returnedItemCount).average().orElse(0);
        double degradedRate = samples.stream().filter(RunSample::degraded).count() * 100.0 / samples.size();
        RunSample first = samples.get(0);

        return new BenchmarkResult(
                key.level(),
                key.scenario().name(),
                key.scenario().userId(),
                UserLayer.bucket(key.scenario().userId()),
                iterations,
                averageCost,
                percentile(costs, 0.50),
                percentile(costs, 0.95),
                averageRecall,
                averageReturned,
                degradedRate,
                first.effectiveLimit(),
                first.skippedRecallSources().isEmpty()
                        ? "-"
                        : String.join("+", first.skippedRecallSources())
        );
    }

    private static void printReport(List<BenchmarkResult> results, Path output) {
        System.out.println();
        System.out.println("Degradation benchmark result");
        System.out.printf(
                "%-6s %-16s %6s %6s %7s %7s %7s %8s %8s %9s %6s %s%n",
                "level", "scenario", "bucket", "avgMs", "p50Ms", "p95Ms",
                "recall", "returned", "hitRate", "limit", "runs", "skipped"
        );
        for (BenchmarkResult result : results) {
            System.out.printf(
                    Locale.ROOT,
                    "%-6s %-16s %6d %6.1f %7d %7d %7.1f %8.1f %8.0f%% %9d %6d %s%n",
                    result.level(), result.scenario(), result.userBucket(), result.averageCostMs(),
                    result.p50CostMs(), result.p95CostMs(), result.averageRecallItemCount(),
                    result.averageReturnedItemCount(), result.degradedRatePercent(), result.effectiveLimit(),
                    result.iterations(), result.skippedRecallSources()
            );
        }
        System.out.println();
        System.out.println("CSV report: " + output.toAbsolutePath());
    }

    private static void writeCsv(List<BenchmarkResult> results, Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        lines.add("level,scenario,userId,userBucket,iterations,avgCostMs,p50CostMs,p95CostMs,avgRecallItemCount,avgReturnedItemCount,degradedRatePercent,effectiveLimit,skippedRecallSources");
        for (BenchmarkResult result : results) {
            lines.add(String.format(
                    Locale.ROOT,
                    "%s,%s,%d,%d,%d,%.1f,%d,%d,%.1f,%.1f,%.1f,%d,%s",
                    result.level(), result.scenario(), result.userId(), result.userBucket(), result.iterations(),
                    result.averageCostMs(), result.p50CostMs(), result.p95CostMs(),
                    result.averageRecallItemCount(), result.averageReturnedItemCount(),
                    result.degradedRatePercent(), result.effectiveLimit(), result.skippedRecallSources()
            ));
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("debug.degradation is missing");
        }
        return (Map<String, Object>) value;
    }

    private static int intValue(Map<String, Object> values, String key) {
        return numberValue(values, key);
    }

    private static int numberValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(key + " is not a number");
        }
        return number.intValue();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    private record Scenario(String name, long userId) {
    }

    private record ResultKey(DegradationLevel level, Scenario scenario) {
    }

    private record RunSample(
            DegradationLevel level,
            Scenario scenario,
            long costMs,
            int recallItemCount,
            int returnedItemCount,
            boolean degraded,
            int effectiveLimit,
            List<String> skippedRecallSources
    ) {
    }

    private record BenchmarkResult(
            DegradationLevel level,
            String scenario,
            long userId,
            int userBucket,
            int iterations,
            double averageCostMs,
            long p50CostMs,
            long p95CostMs,
            double averageRecallItemCount,
            double averageReturnedItemCount,
            double degradedRatePercent,
            int effectiveLimit,
            String skippedRecallSources
    ) {
    }
}
