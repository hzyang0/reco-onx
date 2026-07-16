package com.interview.minireco.resilience;

import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.RecallService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResilientRecallServiceTest {
    @Mock
    private RecallService delegate;

    @BeforeEach
    void setUp() {
        when(delegate.source()).thenReturn("live");
    }

    @Test
    void shouldRetryOnceAndReturnSuccessfulResult() {
        Item item = new Item(1L, "Live item", "live", "digital", 0.8);
        when(delegate.recall(any(RecommendContext.class)))
                .thenThrow(new IllegalStateException("temporary error"))
                .thenReturn(List.of(item));
        ResilientRecallService service = service(config(100, 1, 2));
        RecommendContext context = context();

        List<Item> result = service.recall(context);

        assertEquals(List.of(item), result);
        verify(delegate, times(2)).recall(context);
        Map<String, Object> debug = resilienceDebug(context);
        assertEquals("SUCCESS", debug.get("status"));
        assertEquals(2, debug.get("attempts"));
        assertEquals(CircuitState.CLOSED, service.circuitBreaker().getState());
    }

    @Test
    void shouldTimeoutAndUseEmptyFallback() {
        when(delegate.recall(any(RecommendContext.class))).thenAnswer(invocation -> {
            Thread.sleep(500);
            return List.of();
        });
        ResilientRecallService service = service(config(20, 0, 2));
        RecommendContext context = context();
        long start = System.nanoTime();

        List<Item> result = service.recall(context);
        long costMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.isEmpty());
        assertTrue(costMs < 300, "timeout should stop waiting for the slow dependency");
        Map<String, Object> debug = resilienceDebug(context);
        assertEquals("FALLBACK", debug.get("status"));
        assertEquals("timeout", debug.get("reason"));
    }

    @Test
    void shouldOpenCircuitAndSkipThirdDownstreamCall() {
        when(delegate.recall(any(RecommendContext.class)))
                .thenThrow(new IllegalStateException("dependency unavailable"));
        ResilientRecallService service = service(config(100, 0, 2));
        RecommendContext context = context();

        service.recall(context);
        service.recall(context);
        List<Item> thirdResult = service.recall(context);

        assertTrue(thirdResult.isEmpty());
        verify(delegate, times(2)).recall(context);
        assertEquals(CircuitState.OPEN, service.circuitBreaker().getState());
        Map<String, Object> debug = resilienceDebug(context);
        assertEquals("circuit_open", debug.get("reason"));
        assertEquals(0, debug.get("attempts"));
    }

    private ResilientRecallService service(ResilienceConfig config) {
        return new ResilientRecallService(delegate, config, new MetricsRegistry());
    }

    private ResilienceConfig config(long timeoutMs, int retries, int failureThreshold) {
        return new ResilienceConfig(timeoutMs, retries, failureThreshold, 10_000, 1, 2);
    }

    private RecommendContext context() {
        return new RecommendContext("request-1", new RecommendRequest(123L, "mall", 10));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resilienceDebug(RecommendContext context) {
        Map<String, Object> resilience = (Map<String, Object>) context.buildDebugSnapshot().get("resilience");
        return (Map<String, Object>) resilience.get("live");
    }
}
