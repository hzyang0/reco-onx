package com.interview.minireco.observability;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public final class PrometheusMetricsFormatter {
    private static final String PREFIX = "mini_reco_";

    private PrometheusMetricsFormatter() {
    }

    public static String format(MetricsRegistry registry) {
        StringBuilder output = new StringBuilder();
        Set<String> declared = ConcurrentHashMap.newKeySet();
        for (MetricSample sample : registry.samples()) {
            String baseName = PREFIX + sanitize(sample.getName());
            if (sample.getType() == MetricSample.Type.COUNTER) {
                appendCounter(output, declared, baseName, sample);
            } else {
                appendTimer(output, declared, baseName, sample);
            }
        }
        return output.toString();
    }

    private static void appendCounter(
            StringBuilder output,
            Set<String> declared,
            String baseName,
            MetricSample sample
    ) {
        String metricName = baseName + "_total";
        declare(output, declared, metricName, "counter", "Total events recorded for " + sample.getName() + ".");
        appendSample(output, metricName, sample.getTags(), sample.getTotal());
    }

    private static void appendTimer(
            StringBuilder output,
            Set<String> declared,
            String baseName,
            MetricSample sample
    ) {
        String histogramName = baseName + "_seconds";
        declare(output, declared, histogramName, "histogram", "Duration distribution for " + sample.getName() + ".");
        long[] bounds = sample.getBucketUpperBoundsMs();
        long[] counts = sample.getBucketCounts();
        for (int i = 0; i < bounds.length; i++) {
            Map<String, String> labels = new LinkedHashMap<>(sample.getTags());
            labels.put("le", bounds[i] == Long.MAX_VALUE ? "+Inf" : seconds(bounds[i]));
            appendSample(output, histogramName + "_bucket", labels, counts[i]);
        }
        appendSample(output, histogramName + "_count", sample.getTags(), sample.getCount());
        appendSample(output, histogramName + "_sum", sample.getTags(), seconds(sample.getTotal()));

        String maxName = histogramName + "_max";
        declare(output, declared, maxName, "gauge", "Maximum observed duration for " + sample.getName() + ".");
        appendSample(output, maxName, sample.getTags(), seconds(sample.getMax()));
    }

    private static void declare(
            StringBuilder output,
            Set<String> declared,
            String metricName,
            String type,
            String help
    ) {
        if (declared.add(metricName)) {
            output.append("# HELP ").append(metricName).append(' ').append(help).append('\n');
            output.append("# TYPE ").append(metricName).append(' ').append(type).append('\n');
        }
    }

    private static void appendSample(
            StringBuilder output,
            String metricName,
            Map<String, String> labels,
            long value
    ) {
        output.append(metricName).append(formatLabels(labels)).append(' ').append(value).append('\n');
    }

    private static void appendSample(
            StringBuilder output,
            String metricName,
            Map<String, String> labels,
            String value
    ) {
        output.append(metricName).append(formatLabels(labels)).append(' ').append(value).append('\n');
    }

    private static String formatLabels(Map<String, String> labels) {
        if (labels.isEmpty()) {
            return "";
        }
        StringBuilder output = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : new TreeMap<>(labels).entrySet()) {
            if (!first) {
                output.append(',');
            }
            output.append(sanitize(entry.getKey()))
                    .append("=\"")
                    .append(escapeLabelValue(entry.getValue()))
                    .append('\"');
            first = false;
        }
        return output.append('}').toString();
    }

    private static String sanitize(String value) {
        String sanitized = value.replaceAll("[^a-zA-Z0-9_:]", "_");
        if (sanitized.isEmpty() || Character.isDigit(sanitized.charAt(0))) {
            return "_" + sanitized;
        }
        return sanitized;
    }

    private static String escapeLabelValue(String value) {
        return value.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\"", "\\\"");
    }

    private static String seconds(long milliseconds) {
        return BigDecimal.valueOf(milliseconds, 3).stripTrailingZeros().toPlainString();
    }
}
