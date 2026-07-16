package com.interview.minireco.service.operator.impl;

import com.interview.minireco.domain.AttrName;
import com.interview.minireco.domain.Item;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.operator.Operator;

import java.util.ArrayList;
import java.util.List;

public class FilterOperator implements Operator {
    public static final String NAME = "filter";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(RecommendContext context) {
        List<Item> sourceItems = context.getRankedItems().isEmpty()
                ? context.getRecalledItems()
                : context.getRankedItems();
        List<Item> result = new ArrayList<>();
        for (Item item : sourceItems) {
            int stock = item.findAttr(AttrName.STOCK)
                    .map(Integer::parseInt)
                    .orElse(0);
            String status = item.findAttr(AttrName.STATUS)
                    .orElse("UNKNOWN");

            if (stock > 0 && "ONLINE".equals(status)) {
                result.add(item);
            }
        }
        context.setFilteredItems(result);
        context.putDebug("filteredItemCount", result.size());
    }
}
