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
    private static final long[] TIMER_BUCKETS_MS = {
            5, 10, 25, 50, 100, 200, 500, 1_000, 2_500, 5_000, 10_000, Long.MAX_VALUE
    };

    private final ConcurrentMap<String, MetricStat> stats = new ConcurrentHashMap<>();

    public static MetricsRegistry global() {
        return GLOBAL;
    }

    public void increment(String name, Map<String, String> tags) {
        stat(name, tags, MetricSample.Type.COUNTER).increment(1);
    }

    public void recordTimer(String name, Map<String, String> tags, long costMs) {
        stat(name, tags, MetricSample.Type.TIMER).record(costMs);
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

    private MetricStat stat(String name, Map<String, String> tags, MetricSample.Type type) {
        Map<String, String> safeTags = new LinkedHashMap<>(tags);
        String key = metricKey(name, safeTags);
        MetricStat metric = stats.computeIfAbsent(key, ignored -> new MetricStat(name, safeTags, type));
        if (metric.type != type) {
            throw new IllegalArgumentException("metric type changed for " + key);
        }
        return metric;
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
        private final MetricSample.Type type;
        private final LongAdder count = new LongAdder();
        private final LongAdder total = new LongAdder();
        private final AtomicLong max = new AtomicLong();
        private final LongAdder[] bucketCounts;

        private MetricStat(String name, Map<String, String> tags, MetricSample.Type type) {
            this.name = name;
            this.tags = Map.copyOf(tags);
            this.type = type;
            this.bucketCounts = type == MetricSample.Type.TIMER
                    ? createBucketCounters()
                    : new LongAdder[0];
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
            for (int i = 0; i < TIMER_BUCKETS_MS.length; i++) {
                if (value <= TIMER_BUCKETS_MS[i]) {
                    bucketCounts[i].increment();
                }
            }
        }

        private MetricSample sample() {
            long[] buckets = new long[bucketCounts.length];
            for (int i = 0; i < bucketCounts.length; i++) {
                buckets[i] = bucketCounts[i].sum();
            }
            return new MetricSample(
                    name,
                    tags,
                    type,
                    count.sum(),
                    total.sum(),
                    max.get(),
                    type == MetricSample.Type.TIMER ? TIMER_BUCKETS_MS : new long[0],
                    buckets
            );
        }

        private static LongAdder[] createBucketCounters() {
            LongAdder[] counters = new LongAdder[TIMER_BUCKETS_MS.length];
            for (int i = 0; i < counters.length; i++) {
                counters[i] = new LongAdder();
            }
            return counters;
        }
    }
}
