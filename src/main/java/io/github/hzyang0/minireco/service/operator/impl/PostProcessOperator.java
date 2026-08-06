package io.github.hzyang0.minireco.service.operator.impl;

import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.operator.Operator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PostProcessOperator implements Operator {
    public static final String NAME = "postProcess";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(RecommendContext context) {
        List<Item> baseItems = context.getFilteredItems();
        SceneLayout layout = SceneLayout.forScene(context.getScene());
        Map<String, List<Item>> itemsBySource = groupBySource(baseItems);
        Map<String, Integer> cursors = new LinkedHashMap<>();
        List<Item> result = new ArrayList<>();

        for (int position = 0; position < context.getLimit(); position++) {
            String expectedSource = layout.sourcePattern().get(position % layout.sourcePattern().size());
            Item selected = poll(itemsBySource, cursors, expectedSource);
            if (selected == null) {
                selected = pollBestRemaining(itemsBySource, cursors, layout.allowedSources());
            }
            if (selected == null) {
                break;
            }
            result.add(selected);
        }

        context.setFinalItems(result);
        context.putDebug("returnedItemCount", result.size());
        context.putDebug("scenePolicy", Map.of(
                "scene", context.getScene(),
                "description", layout.description(),
                "sourcePattern", layout.sourcePattern(),
                "returnedBySource", countBySource(result)
        ));
    }

    private Map<String, List<Item>> groupBySource(List<Item> items) {
        Map<String, List<Item>> grouped = new LinkedHashMap<>();
        for (Item item : items) {
            grouped.computeIfAbsent(item.getSource(), ignored -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    private Item poll(Map<String, List<Item>> itemsBySource, Map<String, Integer> cursors, String source) {
        List<Item> items = itemsBySource.getOrDefault(source, List.of());
        int cursor = cursors.getOrDefault(source, 0);
        if (cursor >= items.size()) {
            return null;
        }
        cursors.put(source, cursor + 1);
        return items.get(cursor);
    }

    private Item pollBestRemaining(
            Map<String, List<Item>> itemsBySource,
            Map<String, Integer> cursors,
            List<String> allowedSources
    ) {
        String bestSource = null;
        Item bestItem = null;
        for (String source : allowedSources) {
            List<Item> items = itemsBySource.getOrDefault(source, List.of());
            int cursor = cursors.getOrDefault(source, 0);
            if (cursor < items.size() && (bestItem == null || items.get(cursor).getScore() > bestItem.getScore())) {
                bestSource = source;
                bestItem = items.get(cursor);
            }
        }
        return bestSource == null ? null : poll(itemsBySource, cursors, bestSource);
    }

    private Map<String, Integer> countBySource(List<Item> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Item item : items) {
            counts.merge(item.getSource(), 1, Integer::sum);
        }
        return counts;
    }

    private record SceneLayout(
            List<String> sourcePattern,
            List<String> allowedSources,
            String description
    ) {
        private static SceneLayout forScene(String scene) {
            return switch (scene) {
                case "mall" -> new SceneLayout(
                        List.of("goods", "goods", "goods", "ad", "goods"),
                        List.of("goods", "ad"),
                        "商城：商品为主，广告固定穿插"
                );
                case "video_feed" -> new SceneLayout(
                        List.of("live", "live", "live", "ad", "live"),
                        List.of("live", "ad"),
                        "视频流：直播/视频内容为主，广告固定穿插"
                );
                case "buy_first" -> new SceneLayout(
                        List.of("goods", "live", "goods", "ad", "live"),
                        List.of("goods", "live", "ad"),
                        "买家首页：商品与直播混合，广告固定穿插"
                );
                default -> throw new IllegalArgumentException("unsupported scene: " + scene);
            };
        }
    }
}
