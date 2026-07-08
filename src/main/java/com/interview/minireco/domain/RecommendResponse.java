package com.interview.minireco.domain;

import java.util.List;
import java.util.Map;

public class RecommendResponse {
    private final String requestId;
    private final long userId;
    private final String scene;
    private final long costMs;
    private final List<Item> items;
    private final Map<String, Object> debug;

    public RecommendResponse(String requestId, long userId, String scene, long costMs, List<Item> items, Map<String, Object> debug) {
        this.requestId = requestId;
        this.userId = userId;
        this.scene = scene;
        this.costMs = costMs;
        this.items = List.copyOf(items);
        this.debug = Map.copyOf(debug);
    }

    public String getRequestId() {
        return requestId;
    }

    public long getUserId() {
        return userId;
    }

    public String getScene() {
        return scene;
    }

    public long getCostMs() {
        return costMs;
    }

    public List<Item> getItems() {
        return items;
    }

    public Map<String, Object> getDebug() {
        return debug;
    }
}
