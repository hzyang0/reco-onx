package com.interview.minireco.observability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class MetricsRegistry {
    private static final MetricsRegistry GLOBAL = new MetricsRegistry();

    private final ConcurrentMap<String, MetricStat> stats = new ConcurrentHashMap<>();

    public static MetricsRegistry global() {
        return GLOBAL;
    }

    public void increment(String name, Map<String, String> tags) {
        stat(name, tags).increment(1);
    }

    public void recordTimer(String name, Map<String, String> tags, long costMs) {
        stat(name, tags).record(costMs);
    }

    public List<MetricSample> samples() {
        List<MetricSample> result = new ArrayList<>();
        for (MetricStat stat : stats.values()) {
            result.add(stat.sample());
        }
        result.sort(Comparator
                .comparing(MetricSample::getName)
                .thenComparing(sample -> sample.getTags().toString()));
        return result;
    }

    public Map<String, Object> snapshot() {
        List<Object> metrics = new ArrayList<>();
        for (MetricSample sample : samples()) {
            metrics.add(sample.toMap());
        }
        return Map.of("metrics", metrics);
    }

    public void clear() {
        stats.clear();
    }

    private MetricStat stat(String name, Map<String, String> tags) {
        Map<String, String> safeTags = new LinkedHashMap<>(tags);
        String key = metricKey(name, safeTags);
        return stats.computeIfAbsent(key, ignored -> new MetricStat(name, safeTags));
    }

    private String metricKey(String name, Map<String, String> tags) {
        StringJoiner joiner = new StringJoiner(",", name + "{", "}");
        tags.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> joiner.add(entry.getKey() + "=" + entry.getValue()));
        return joiner.toString();
    }

    private static class MetricStat {
        private final String name;
        private final Map<String, String> tags;
        private final LongAdder count = new LongAdder();
        private final LongAdder total = new LongAdder();
        private final AtomicLong max = new AtomicLong();

        private MetricStat(String name, Map<String, String> tags) {
            this.name = name;
            this.tags = Map.copyOf(tags);
        }

        private void increment(long value) {
            count.add(value);
            total.add(value);
            max.accumulateAndGet(value, Math::max);
        }

        private void record(long value) {
            count.increment();
            total.add(value);
            max.accumulateAndGet(value, Math::max);
        }

        private MetricSample sample() {
            return new MetricSample(name, tags, count.sum(), total.sum(), max.get());
        }
    }
}
