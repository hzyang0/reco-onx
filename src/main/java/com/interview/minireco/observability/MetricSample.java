package com.interview.minireco.observability;

import java.util.Map;

public class MetricSample {
    public enum Type {
        COUNTER,
        TIMER
    }

    private final String name;
    private final Map<String, String> tags;
    private final Type type;
    private final long count;
    private final long total;
    private final long max;
    private final long[] bucketUpperBoundsMs;
    private final long[] bucketCounts;

    public MetricSample(
            String name,
            Map<String, String> tags,
            Type type,
            long count,
            long total,
            long max,
            long[] bucketUpperBoundsMs,
            long[] bucketCounts
    ) {
        this.name = name;
        this.tags = Map.copyOf(tags);
        this.type = type;
        this.count = count;
        this.total = total;
        this.max = max;
        this.bucketUpperBoundsMs = bucketUpperBoundsMs.clone();
        this.bucketCounts = bucketCounts.clone();
    }

    public String getName() {
        return name;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public Type getType() {
        return type;
    }

    public long getCount() {
        return count;
    }

    public long getTotal() {
        return total;
    }

    public long getMax() {
        return max;
    }

    public long[] getBucketUpperBoundsMs() {
        return bucketUpperBoundsMs.clone();
    }

    public long[] getBucketCounts() {
        return bucketCounts.clone();
    }

    public double getAvg() {
        if (count == 0) {
            return 0;
        }
        return Math.round((total * 100.0 / count)) / 100.0;
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "name", name,
                "tags", tags,
                "type", type.name().toLowerCase(),
                "count", count,
                "total", total,
                "avg", getAvg(),
                "max", max
        );
    }
}
