package com.interview.minireco.service.operator.impl;

import com.interview.minireco.degradation.DegradationDecision;
import com.interview.minireco.degradation.DegradationLevel;
import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.RecallService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
