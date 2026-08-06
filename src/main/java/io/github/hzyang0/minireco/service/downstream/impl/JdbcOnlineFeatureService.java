package io.github.hzyang0.minireco.service.downstream.impl;

import io.github.hzyang0.minireco.domain.AttrName;
import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.service.data.JdbcDataRepository;
import io.github.hzyang0.minireco.service.downstream.OnlineFeatureService;

import java.util.List;
import java.util.Map;

public final class JdbcOnlineFeatureService implements OnlineFeatureService {
    private final JdbcDataRepository repository;

    public JdbcOnlineFeatureService(JdbcDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public void fillOnlineFeatures(List<Item> items) {
        Map<Long, JdbcDataRepository.Inventory> inventoryByItemId = repository.findInventoryByItemIds(
                items.stream().map(Item::getItemId).toList()
        );
        for (Item item : items) {
            JdbcDataRepository.Inventory inventory = inventoryByItemId.getOrDefault(
                    item.getItemId(), new JdbcDataRepository.Inventory(item.getItemId(), 0, 0, "UNKNOWN")
            );
            item.putAttr(AttrName.PRICE, String.valueOf(inventory.price()));
            item.putAttr(AttrName.STOCK, String.valueOf(inventory.stock()));
            item.putAttr(AttrName.STATUS, inventory.status());
        }
    }
}
