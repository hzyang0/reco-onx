package com.interview.minireco.domain;

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
    CREATIVE_ID("creative_id");

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
