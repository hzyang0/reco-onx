package com.interview.minireco.cache;

public interface FeatureCacheClient extends AutoCloseable {
    String get(String key);

    void set(String key, String value, long ttlSeconds);

    @Override
    default void close() {
    }
}
