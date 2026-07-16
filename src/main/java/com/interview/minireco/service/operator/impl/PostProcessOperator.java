package com.interview.minireco.service.operator.impl;

import com.interview.minireco.domain.AttrName;
import com.interview.minireco.domain.Item;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.operator.Operator;

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
        List<Item> result = new ArrayList<>(context.getRankedItems());
        int index = 0;
        while (result.size() < context.getLimit()) {
            Item fallback = new Item(90_000L + index, "Fallback hot item-" + index, "fallback", "hot", 0.30);
            fallback.putAttr(AttrName.PRICE, String.valueOf(19 + index));
            fallback.putAttr(AttrName.STOCK, "999");
            fallback.putAttr(AttrName.STATUS, "ONLINE");
            fallback.putAttr(AttrName.RECALL_REASON, "fallback");
            result.add(fallback);
            index++;
        }
        context.setFinalItems(result);
        context.putDebug("returnedItemCount", result.size());
    }
}
