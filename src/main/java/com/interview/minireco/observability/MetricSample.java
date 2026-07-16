package com.interview.minireco.observability;

import java.util.Map;

public class MetricSample {
    private final String name;
    private final Map<String, String> tags;
    private final long count;
    private final long total;
    private final long max;

    public MetricSample(String name, Map<String, String> tags, long count, long total, long max) {
        this.name = name;
        this.tags = Map.copyOf(tags);
        this.count = count;
        this.total = total;
        this.max = max;
    }

    public String getName() {
        return name;
    }

    public Map<String, String> getTags() {
        return tags;
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
                "count", count,
                "total", total,
                "avg", getAvg(),
                "max", max
        );
    }
}
