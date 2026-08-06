package io.github.hzyang0.minireco.service.operator.impl;

import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.domain.RecommendRequest;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostProcessOperatorTest {
    private final PostProcessOperator operator = new PostProcessOperator();

    @Test
    void mallShouldReturnGoodsWithAdsAtFixedSlots() {
        RecommendContext context = context("mall");

        operator.execute(context);

        assertEquals(
                List.of("goods", "goods", "goods", "ad", "goods", "goods", "goods", "goods", "ad", "goods"),
                sources(context)
        );
        assertReturnedCounts(context, Map.of("goods", 8, "ad", 2));
    }

    @Test
    void videoFeedShouldReturnLiveContentWithAdsAtFixedSlots() {
        RecommendContext context = context("video_feed");

        operator.execute(context);

        assertEquals(
                List.of("live", "live", "live", "ad", "live", "live", "live", "live", "ad", "live"),
                sources(context)
        );
        assertReturnedCounts(context, Map.of("live", 8, "ad", 2));
    }

    @Test
    void buyerHomeShouldMixGoodsLiveAndAds() {
        RecommendContext context = context("buy_first");

        operator.execute(context);

        assertEquals(
                List.of("goods", "live", "goods", "ad", "live", "goods", "live", "goods", "ad", "live"),
                sources(context)
        );
        assertReturnedCounts(context, Map.of("goods", 4, "live", 4, "ad", 2));
    }

    private RecommendContext context(String scene) {
        RecommendContext context = new RecommendContext("request-1", new RecommendRequest(123L, scene, 10));
        List<Item> candidates = new ArrayList<>();
        addItems(candidates, "goods", 1000L);
        addItems(candidates, "live", 2000L);
        addItems(candidates, "ad", 3000L);
        context.setFilteredItems(candidates);
        return context;
    }

    private void addItems(List<Item> candidates, String source, long firstId) {
        for (int index = 0; index < 10; index++) {
            candidates.add(new Item(firstId + index, source + index, source, "digital", 1.0 - index * 0.01));
        }
    }

    private List<String> sources(RecommendContext context) {
        return context.getFinalItems().stream().map(Item::getSource).toList();
    }

    @SuppressWarnings("unchecked")
    private void assertReturnedCounts(RecommendContext context, Map<String, Integer> expected) {
        Map<String, Object> policy = (Map<String, Object>) context.buildDebugSnapshot().get("scenePolicy");
        assertEquals(expected, policy.get("returnedBySource"));
    }
}
