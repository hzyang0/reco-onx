package com.interview.minireco.telemetry.collector;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.Span;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

final class TraceStore {
    private final ConcurrentMap<String, ConcurrentLinkedQueue<StoredSpan>> traces = new ConcurrentHashMap<>();

    void record(ExportTraceServiceRequest request) {
        for (ResourceSpans resourceSpans : request.getResourceSpansList()) {
            String serviceName = serviceName(resourceSpans);
            resourceSpans.getScopeSpansList().forEach(scopeSpans ->
                    scopeSpans.getSpansList().forEach(span -> record(serviceName, span))
            );
        }
    }

    Map<String, Object> find(String traceId) {
        List<StoredSpan> spans = new ArrayList<>(
                traces.getOrDefault(traceId, new ConcurrentLinkedQueue<>())
        );
        spans.sort(Comparator.comparingLong(StoredSpan::startTimeUnixNano));
        Set<String> serviceNames = new TreeSet<>();
        List<Object> serialized = new ArrayList<>();
        for (StoredSpan span : spans) {
            serviceNames.add(span.serviceName());
            serialized.add(span.toMap());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("found", !spans.isEmpty());
        result.put("spanCount", spans.size());
        result.put("serviceNames", new ArrayList<>(serviceNames));
        result.put("spans", serialized);
        return result;
    }

    int traceCount() {
        return traces.size();
    }

    private void record(String serviceName, Span span) {
        String traceId = hex(span.getTraceId());
        if (traceId.isBlank()) {
            return;
        }
        traces.computeIfAbsent(traceId, ignored -> new ConcurrentLinkedQueue<>())
                .add(new StoredSpan(
                        traceId,
                        hex(span.getSpanId()),
                        hex(span.getParentSpanId()),
                        serviceName,
                        span.getName(),
                        span.getKind().name(),
                        span.getStartTimeUnixNano(),
                        span.getEndTimeUnixNano()
                ));
    }

    private String serviceName(ResourceSpans resourceSpans) {
        for (KeyValue attribute : resourceSpans.getResource().getAttributesList()) {
            if ("service.name".equals(attribute.getKey())) {
                AnyValue value = attribute.getValue();
                if (value.hasStringValue() && !value.getStringValue().isBlank()) {
                    return value.getStringValue();
                }
            }
        }
        return "unknown-service";
    }

    private String hex(ByteString bytes) {
        return HexFormat.of().formatHex(bytes.toByteArray());
    }

    private record StoredSpan(
            String traceId,
            String spanId,
            String parentSpanId,
            String serviceName,
            String name,
            String kind,
            long startTimeUnixNano,
            long endTimeUnixNano
    ) {
        private Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("traceId", traceId);
            data.put("spanId", spanId);
            data.put("parentSpanId", parentSpanId);
            data.put("serviceName", serviceName);
            data.put("name", name);
            data.put("kind", kind);
            data.put("durationMicros", Math.max(0, endTimeUnixNano - startTimeUnixNano) / 1_000);
            return data;
        }
    }
}
