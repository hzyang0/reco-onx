package com.interview.minireco.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

public class FeatureCacheStats {
    private static final FeatureCacheStats GLOBAL = new FeatureCacheStats();

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder originLoads = new LongAdder();
    private final LongAdder singleFlightJoins = new LongAdder();
    private final LongAdder writes = new LongAdder();

    public static FeatureCacheStats global() {
        return GLOBAL;
    }

    public void hit() { hits.increment(); }
    public void miss() { misses.increment(); }
    public void error() { errors.increment(); }
    public void originLoad() { originLoads.increment(); }
    public void singleFlightJoin() { singleFlightJoins.increment(); }
    public void write() { writes.increment(); }

    public Map<String, Object> snapshot(boolean enabled) {
        long hit = hits.sum();
        long miss = misses.sum();
        long requests = hit + miss;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        map.put("hits", hit);
        map.put("misses", miss);
        map.put("errors", errors.sum());
        map.put("originLoads", originLoads.sum());
        map.put("singleFlightJoins", singleFlightJoins.sum());
        map.put("writes", writes.sum());
        map.put("hitRate", requests == 0 ? 0.0 : Math.round(hit * 10_000.0 / requests) / 100.0);
        return map;
    }

    public void reset() {
        hits.reset(); misses.reset(); errors.reset(); originLoads.reset(); singleFlightJoins.reset(); writes.reset();
    }
}
