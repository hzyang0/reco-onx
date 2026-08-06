package io.github.hzyang0.minireco.service.operator.impl;

import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.operator.Operator;

import java.util.ArrayList;
import java.util.List;

public class PostProcessOperator implements Operator {
    public static final String NAME = "postProcess";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(RecommendContext context) {
        List<Item> baseItems = context.getFilteredItems();
        List<Item> result = new ArrayList<>(baseItems);
        if (result.size() > context.getLimit()) {
            result = new ArrayList<>(result.subList(0, context.getLimit()));
        }
        context.setFinalItems(result);
        context.putDebug("returnedItemCount", result.size());
    }
}
