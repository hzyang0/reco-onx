package com.interview.minireco.cache;

import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.service.downstream.UserFeatureService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachedUserFeatureServiceTest {
    @Test
    void shouldCacheAsideWithTtlJitter() {
        UserFeatureService origin = mock(UserFeatureService.class);
        UserFeature expected = new UserFeature(123, false, "digital", 23);
        when(origin.getUserFeature(123)).thenReturn(expected);
        RecordingCache cache = new RecordingCache();
        CachedUserFeatureService service = service(origin, cache);

        assertEquals("digital", service.getUserFeature(123).getPreferredCategory());
        assertEquals("digital", service.getUserFeature(123).getPreferredCategory());

        verify(origin, times(1)).getUserFeature(123);
        assertTrue(cache.ttl >= 60 && cache.ttl <= 75);
    }

    @Test
    void shouldBypassBrokenRedisWithoutBreakingOrigin() {
        UserFeatureService origin = mock(UserFeatureService.class);
        when(origin.getUserFeature(7)).thenReturn(new UserFeature(7, true, "fashion", 27));
        FeatureCacheClient broken = new FeatureCacheClient() {
            public String get(String key) { throw new IllegalStateException("redis down"); }
            public void set(String key, String value, long ttl) { throw new IllegalStateException("redis down"); }
        };

        UserFeature result = service(origin, broken).getUserFeature(7);

        assertEquals(7, result.getUserId());
        verify(origin, times(1)).getUserFeature(7);
    }

    @Test
    void shouldCollapseConcurrentMissesIntoOneOriginLoad() throws Exception {
        UserFeatureService origin = mock(UserFeatureService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(origin.getUserFeature(42)).thenAnswer(ignored -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return new UserFeature(42, false, "food", 22);
        });
        CachedUserFeatureService service = service(origin, new RecordingCache());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<UserFeature>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> service.getUserFeature(42)));
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            Thread.sleep(50);
            release.countDown();
            for (Future<UserFeature> future : futures) {
                assertEquals(42, future.get(2, TimeUnit.SECONDS).getUserId());
            }
        } finally {
            executor.shutdownNow();
        }
        verify(origin, times(1)).getUserFeature(42);
    }

    @Test
    void shouldShortCacheNullToProtectAgainstPenetration() {
        UserFeatureService origin = mock(UserFeatureService.class);
        when(origin.getUserFeature(404)).thenReturn(null);
        RecordingCache cache = new RecordingCache();
        CachedUserFeatureService service = service(origin, cache);

        assertEquals(null, service.getUserFeature(404));
        assertEquals(null, service.getUserFeature(404));

        verify(origin, times(1)).getUserFeature(404);
        assertEquals(CachedUserFeatureService.NULL_SENTINEL, cache.value);
        assertEquals(10, cache.ttl);
    }

    private CachedUserFeatureService service(UserFeatureService origin, FeatureCacheClient cache) {
        return new CachedUserFeatureService(origin, cache, 60, 15, 10,
                new FeatureCacheStats(), new MetricsRegistry());
    }

    private static final class RecordingCache implements FeatureCacheClient {
        private volatile String value;
        private volatile long ttl;

        public String get(String key) { return value; }
        public void set(String key, String value, long ttlSeconds) {
            this.value = value;
            this.ttl = ttlSeconds;
        }
    }
}
