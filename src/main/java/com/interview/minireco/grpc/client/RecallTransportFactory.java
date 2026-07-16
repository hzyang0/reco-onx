package com.interview.minireco.grpc.client;

import com.interview.minireco.service.downstream.RecallService;
import com.interview.minireco.service.downstream.impl.AdRecallService;
import com.interview.minireco.service.downstream.impl.GoodsRecallService;
import com.interview.minireco.service.downstream.impl.LiveRecallService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RecallTransportFactory {
    private RecallTransportFactory() {
    }

    public static List<RecallService> createRecallServices() {
        if ("local".equals(mode())) {
            return List.of(
                    new GoodsRecallService(),
                    new LiveRecallService(),
                    new AdRecallService()
            );
        }

        GrpcRecallClientConfig config = GrpcRecallClientConfig.fromSystemProperties();
        List<RecallService> services = List.of(
                new GrpcGoodsRecallService(config.goodsTarget(), config.deadlineMs()),
                new GrpcLiveRecallService(config.liveTarget(), config.deadlineMs()),
                new GrpcAdRecallService(config.adTarget(), config.deadlineMs())
        );
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> services.stream()
                        .filter(AutoCloseable.class::isInstance)
                        .map(AutoCloseable.class::cast)
                        .forEach(RecallTransportFactory::closeQuietly),
                "grpc-recall-channel-shutdown"
        ));
        return services;
    }

    public static String mode() {
        String configured = System.getProperty("reco.recall.transport");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv().getOrDefault("RECALL_TRANSPORT", "local");
        }
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        if (!"local".equals(normalized) && !"grpc".equals(normalized)) {
            throw new IllegalArgumentException("reco.recall.transport must be local or grpc");
        }
        return normalized;
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", mode());
        if ("grpc".equals(mode())) {
            data.put("grpc", GrpcRecallClientConfig.fromSystemProperties().toMap());
        }
        return data;
    }

    private static void closeQuietly(AutoCloseable service) {
        try {
            service.close();
        } catch (Exception ignored) {
            // JVM is already shutting down; there is no caller left to recover.
        }
    }
}
