package com.interview.minireco.service.operator.impl;

import com.interview.minireco.domain.Item;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.RecallService;
import com.interview.minireco.service.operator.Operator;

import java.util.ArrayList;
import java.util.List;

public class RecallOperator implements Operator {
    public static final String NAME = "recall";

    private final List<RecallService> recallServices;

    public RecallOperator(List<RecallService> recallServices) {
        this.recallServices = List.copyOf(recallServices);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(RecommendContext context) {
        List<Item> items = new ArrayList<>();
        for (RecallService recallService : recallServices) {
            List<Item> recalled = recallService.recall(context);
            items.addAll(recalled);
        }
        context.setRecalledItems(items);
        context.putDebug("recallItemCount", items.size());
    }
}
