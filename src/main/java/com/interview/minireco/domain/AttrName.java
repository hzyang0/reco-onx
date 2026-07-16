package com.interview.minireco.domain;

public enum AttrName {
    PRICE("price"),
    STOCK("stock"),
    STATUS("status"),
    RECALL_REASON("recall_reason");

    private final String key;

    AttrName(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
