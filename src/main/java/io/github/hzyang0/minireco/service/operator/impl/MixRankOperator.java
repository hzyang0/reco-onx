package io.github.hzyang0.minireco.service.operator.impl;

import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.downstream.MixRankService;
import io.github.hzyang0.minireco.service.operator.Operator;

import java.util.List;

public class MixRankOperator implements Operator {
    public static final String NAME = "mixRank";

    private final MixRankService mixRankService;

    public MixRankOperator(MixRankService mixRankService) {
        this.mixRankService = mixRankService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(RecommendContext context) {
        List<Item> candidates = context.getFilteredItems().isEmpty()
                ? context.getRecalledItems()
                : context.getFilteredItems();
        // Rank a wider window before stock/status filtering. Otherwise a sold-out
        // item near the top could leave too few valid database candidates.
        int rankWindow = Math.max(context.getLimit() * 3, 10);
        List<Item> rankedItems = mixRankService.rank(candidates, context, rankWindow);
        context.setRankedItems(rankedItems);
    }
}
