package com.interview.minireco.cache;

import redis.clients.jedis.RedisClient;
import redis.clients.jedis.params.SetParams;

public class RedisFeatureCacheClient implements FeatureCacheClient {
    private final RedisClient client;

    public RedisFeatureCacheClient(String redisUrl) {
        this.client = RedisClient.create(redisUrl);
    }

    @Override
    public String get(String key) {
        return client.get(key);
    }

    @Override
    public void set(String key, String value, long ttlSeconds) {
        client.set(key, value, SetParams.setParams().ex(ttlSeconds));
    }

    @Override
    public void close() {
        client.close();
    }
}
