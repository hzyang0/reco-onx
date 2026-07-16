package com.interview.minireco.grpc.client;

import java.util.LinkedHashMap;
import java.util.Map;

public record GrpcRecallClientConfig(
        String goodsTarget,
        String liveTarget,
        String adTarget,
        long deadlineMs
) {
    public GrpcRecallClientConfig {
        if (goodsTarget == null || goodsTarget.isBlank()
                || liveTarget == null || liveTarget.isBlank()
                || adTarget == null || adTarget.isBlank()) {
            throw new IllegalArgumentException("gRPC recall targets must not be blank");
        }
        if (deadlineMs <= 0) {
            throw new IllegalArgumentException("gRPC deadline must be positive");
        }
    }

    public static GrpcRecallClientConfig fromSystemProperties() {
        return new GrpcRecallClientConfig(
                property("reco.grpc.goods.target", "GRPC_GOODS_TARGET", "localhost:19001"),
                property("reco.grpc.live.target", "GRPC_LIVE_TARGET", "localhost:19002"),
                property("reco.grpc.ad.target", "GRPC_AD_TARGET", "localhost:19003"),
                longProperty("reco.grpc.deadline.ms", "GRPC_DEADLINE_MS", 70L)
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("goodsTarget", goodsTarget);
        data.put("liveTarget", liveTarget);
        data.put("adTarget", adTarget);
        data.put("deadlineMs", deadlineMs);
        return data;
    }

    private static String property(String name, String envName, String defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(envName);
        }
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static long longProperty(String name, String envName, long defaultValue) {
        String value = property(name, envName, Long.toString(defaultValue));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a long", e);
        }
    }
}
