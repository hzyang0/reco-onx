package com.interview.minireco.proto.adapter;

import com.interview.minireco.domain.AttrName;
import com.interview.minireco.domain.Item;
import com.interview.minireco.proto.internal.InternalItemPb;
import com.interview.minireco.proto.internal.InternalRecallResultPb;

import java.util.ArrayList;
import java.util.List;

public final class InternalItemDomainAdapter {
    private InternalItemDomainAdapter() {
    }

    public static List<Item> toDomain(InternalRecallResultPb result) {
        List<Item> items = new ArrayList<>(result.getItemsCount());
        for (InternalItemPb sourceItem : result.getItemsList()) {
            Item item = new Item(
                    sourceItem.getItemId(),
                    sourceItem.getTitle(),
                    sourceItem.getSource(),
                    sourceItem.getCategory(),
                    sourceItem.getScore()
            );
            sourceItem.getAttrsMap().forEach((key, value) ->
                    AttrName.fromKey(key).ifPresent(name -> item.putAttr(name, value))
            );
            items.add(item);
        }
        return items;
    }
}
