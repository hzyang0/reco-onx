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
        Map<Long, JdbcDataRepository.OnlineSnapshot> snapshots = repository.findOnlineSnapshots(
                items.stream().map(Item::getItemId).toList()
        );
        for (Item item : items) {
            JdbcDataRepository.OnlineSnapshot snapshot = snapshots.get(item.getItemId());
            if (snapshot == null) {
                item.putAttr(AttrName.STATUS, "UNKNOWN");
                continue;
            }
            switch (snapshot.source()) {
                case "goods" -> fillGoods(item, snapshot);
                case "live" -> fillLive(item, snapshot);
                case "ad" -> fillAd(item, snapshot);
                default -> item.putAttr(AttrName.STATUS, "UNKNOWN");
            }
        }
    }

    private void fillGoods(Item item, JdbcDataRepository.OnlineSnapshot snapshot) {
        item.putAttr(AttrName.PRICE, String.valueOf(valueOrZero(snapshot.price())));
        item.putAttr(AttrName.STOCK, String.valueOf(valueOrZero(snapshot.stock())));
        item.putAttr(AttrName.STATUS, valueOrUnknown(snapshot.goodsStatus()));
    }

    private void fillLive(Item item, JdbcDataRepository.OnlineSnapshot snapshot) {
        putIfPresent(item, AttrName.ROOM_ID, snapshot.roomId());
        putIfPresent(item, AttrName.ANCHOR_ID, snapshot.anchorId());
        item.putAttr(AttrName.HEAT, String.valueOf(valueOrZero(snapshot.heat())));
        item.putAttr(AttrName.STATUS, valueOrUnknown(snapshot.liveStatus()));
    }

    private void fillAd(Item item, JdbcDataRepository.OnlineSnapshot snapshot) {
        putIfPresent(item, AttrName.CREATIVE_ID, snapshot.creativeId());
        putIfPresent(item, AttrName.CAMPAIGN_ID, snapshot.campaignId());
        if (snapshot.promotedItemId() != null) {
            item.putAttr(AttrName.PROMOTED_ITEM_ID, String.valueOf(snapshot.promotedItemId()));
        }
        item.putAttr(AttrName.BID_CENTS, String.valueOf(valueOrZero(snapshot.bidCents())));
        item.putAttr(
                AttrName.REMAINING_BUDGET_CENTS,
                String.valueOf(snapshot.remainingBudgetCents() == null ? 0 : snapshot.remainingBudgetCents())
        );
        item.putAttr(AttrName.STATUS, valueOrUnknown(snapshot.adStatus()));
    }

    private void putIfPresent(Item item, AttrName name, String value) {
        if (value != null && !value.isBlank()) {
            item.putAttr(name, value);
        }
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String valueOrUnknown(String value) {
        return value == null ? "UNKNOWN" : value;
    }
}
