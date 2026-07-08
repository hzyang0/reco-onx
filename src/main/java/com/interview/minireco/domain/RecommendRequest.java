package com.interview.minireco.domain;

public class RecommendRequest {
    private final long userId;
    private final String scene;
    private final int limit;

    public RecommendRequest(long userId, String scene, int limit) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (scene == null || scene.isBlank()) {
            throw new IllegalArgumentException("scene must not be blank");
        }
        if (limit <= 0 || limit > 50) {
            throw new IllegalArgumentException("limit must be between 1 and 50");
        }
        this.userId = userId;
        this.scene = scene;
        this.limit = limit;
    }

    public long getUserId() {
        return userId;
    }

    public String getScene() {
        return scene;
    }

    public int getLimit() {
        return limit;
    }
}
