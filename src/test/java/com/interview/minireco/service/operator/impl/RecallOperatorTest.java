package com.interview.minireco.service.operator.impl;

import com.interview.minireco.degradation.DegradationDecision;
import com.interview.minireco.degradation.DegradationLevel;
import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.RecallService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecallOperatorTest {
    @Test
    void recallShouldSkipSourcesFromDegradationDecision() {
        AtomicBoolean adCalled = new AtomicBoolean(false);
        RecallService goods = recallService("goods", new Item(1L, "Goods item", "goods", "digital", 0.9));
        RecallService ad = new RecallService() {
            @Override
            public String source() {
                return "ad";
            }

            @Override
            public List<Item> recall(RecommendContext context) {
                adCalled.set(true);
                return List.of(new Item(2L, "Ad item", "ad", "ad", 0.1));
            }
        };

        RecommendContext context = new RecommendContext("request-1", new RecommendRequest(185L, "mall", 10));
        context.setDegradationDecision(new DegradationDecision(
                DegradationLevel.LIGHT,
                85,
                true,
                10,
                8,
                List.of("ad")
        ));

        new RecallOperator(List.of(goods, ad)).execute(context);

        assertFalse(adCalled.get());
        assertEquals(1, context.getRecalledItems().size());
        assertEquals("goods", context.getRecalledItems().get(0).getSource());
        assertEquals(1, context.buildDebugSnapshot().get("recallItemCount"));
    }

    @Test
    void recallShouldStartAllSourcesConcurrentlyAndKeepConfiguredOrder() {
        CountDownLatch allStarted = new CountDownLatch(3);
        RecallService goods = concurrentService("goods", 1L, allStarted);
        RecallService live = concurrentService("live", 2L, allStarted);
        RecallService ad = concurrentService("ad", 3L, allStarted);
        RecallOperator operator = new RecallOperator(
                List.of(goods, live, ad),
                new RecallFanoutConfig(500, 3, 10),
                new MetricsRegistry()
        );
        RecommendContext context = context();

        operator.execute(context);

        assertEquals(3, context.getRecalledItems().size());
        assertEquals(List.of("goods", "live", "ad"), context.getRecalledItems().stream()
                .map(Item::getSource)
                .toList());
        Map<String, Object> fanout = fanoutDebug(context);
        assertEquals("SUCCESS", fanout.get("status"));
        assertEquals(List.of("goods", "live", "ad"), fanout.get("completedSources"));
    }

    @Test
    void recallShouldReturnFastPartialResultAtOverallDeadline() {
        AtomicBoolean slowCallInterrupted = new AtomicBoolean(false);
        RecallService fast = recallService("goods", new Item(1L, "Goods", "goods", "digital", 0.9));
        RecallService slow = new RecallService() {
            @Override
            public String source() {
                return "live";
            }

            @Override
            public List<Item> recall(RecommendContext context) {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException e) {
                    slowCallInterrupted.set(true);
                    Thread.currentThread().interrupt();
                }
                return List.of(new Item(2L, "Late live", "live", "digital", 0.8));
            }
        };
        RecallOperator operator = new RecallOperator(
                List.of(fast, slow),
                new RecallFanoutConfig(50, 2, 10),
                new MetricsRegistry()
        );
        RecommendContext context = context();
        long start = System.nanoTime();

        operator.execute(context);
        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(costMs < 300, "operator must not wait for the slow recall source");
        assertEquals(1, context.getRecalledItems().size());
        assertEquals("goods", context.getRecalledItems().get(0).getSource());
        Map<String, Object> fanout = fanoutDebug(context);
        assertEquals("PARTIAL", fanout.get("status"));
        assertEquals(List.of("live"), fanout.get("timedOutSources"));
        for (int i = 0; i < 20 && !slowCallInterrupted.get(); i++) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(slowCallInterrupted.get());
    }

    private RecallService recallService(String source, Item item) {
        return new RecallService() {
            @Override
            public String source() {
                return source;
            }

            @Override
            public List<Item> recall(RecommendContext context) {
                return List.of(item);
            }
        };
    }

    private RecallService concurrentService(String source, long itemId, CountDownLatch allStarted) {
        return new RecallService() {
            @Override
            public String source() {
                return source;
            }

            @Override
            public List<Item> recall(RecommendContext context) {
                allStarted.countDown();
                try {
                    if (!allStarted.await(300, TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("recall sources did not start concurrently");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test interrupted", e);
                }
                return List.of(new Item(itemId, source, source, "digital", 0.8));
            }
        };
    }

    private RecommendContext context() {
        return new RecommendContext("request-1", new RecommendRequest(123L, "mall", 10));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fanoutDebug(RecommendContext context) {
        return (Map<String, Object>) context.buildDebugSnapshot().get("recallFanout");
    }
}
