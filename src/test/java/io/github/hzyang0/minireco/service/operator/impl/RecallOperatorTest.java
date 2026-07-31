package io.github.hzyang0.minireco.service.operator.impl;

import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.domain.RecommendRequest;
import io.github.hzyang0.minireco.observability.MetricsRegistry;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.downstream.RecallService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecallOperatorTest {
    @Test
    void recallShouldKeepSuccessfulSourcesWhenOneSourceFails() {
        RecallService goods = recallService("goods", new Item(1L, "Goods item", "goods", "digital", 0.9));
        RecallService live = new RecallService() {
            @Override
            public String source() {
                return "live";
            }

            @Override
            public List<Item> recall(RecommendContext context) {
                throw new IllegalStateException("simulated failure");
            }
        };
        RecommendContext context = context();

        new RecallOperator(List.of(goods, live)).execute(context);

        assertEquals(1, context.getRecalledItems().size());
        assertEquals("goods", context.getRecalledItems().get(0).getSource());
        Map<String, Object> fanout = fanoutDebug(context);
        assertEquals("PARTIAL", fanout.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, String> failedSources = (Map<String, String>) fanout.get("failedSources");
        assertTrue(failedSources.containsKey("live"));
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
