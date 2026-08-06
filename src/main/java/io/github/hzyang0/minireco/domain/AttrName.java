package io.github.hzyang0.minireco.domain;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum AttrName {
    PRICE("price"),
    STOCK("stock"),
    STATUS("status"),
    RECALL_REASON("recall_reason"),
    ROOM_ID("room_id"),
    ANCHOR_ID("anchor_id"),
    HEAT("heat"),
    CREATIVE_ID("creative_id"),
    CAMPAIGN_ID("campaign_id"),
    PROMOTED_ITEM_ID("promoted_item_id"),
    BID_CENTS("bid_cents"),
    REMAINING_BUDGET_CENTS("remaining_budget_cents");

    private static final Map<String, AttrName> BY_KEY = java.util.Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(AttrName::key, Function.identity()));

    private final String key;

    AttrName(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<AttrName> fromKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }
}
