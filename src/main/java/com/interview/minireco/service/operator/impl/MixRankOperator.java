package com.interview.minireco.service.operator.impl;

import com.interview.minireco.domain.Item;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.MixRankService;
import com.interview.minireco.service.operator.Operator;

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
        List<Item> rankedItems = mixRankService.rank(candidates, context, context.getEffectiveLimit());
        context.setRankedItems(rankedItems);
    }
}
