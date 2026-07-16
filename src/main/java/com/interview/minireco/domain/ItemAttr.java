package com.interview.minireco.domain;

public class ItemAttr {
    private final AttrName name;
    private String value;

    public ItemAttr(AttrName name, String value) {
        this.name = name;
        this.value = value;
    }

    public AttrName getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
