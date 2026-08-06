package io.github.hzyang0.minireco.service.operator.impl;

import io.github.hzyang0.minireco.domain.AttrName;
import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.operator.Operator;

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
            String status = item.findAttr(AttrName.STATUS)
                    .orElse("UNKNOWN");

            if ("ONLINE".equals(status) && hasAvailableCapacity(item)) {
                result.add(item);
            }
        }
        context.setFilteredItems(result);
        context.putDebug("filteredItemCount", result.size());
    }

    private boolean hasAvailableCapacity(Item item) {
        AttrName attribute = switch (item.getSource()) {
            case "goods" -> AttrName.STOCK;
            case "live" -> AttrName.HEAT;
            case "ad" -> AttrName.REMAINING_BUDGET_CENTS;
            default -> null;
        };
        return attribute != null && item.findAttr(attribute)
                .map(Long::parseLong)
                .orElse(0L) > 0;
    }
}
