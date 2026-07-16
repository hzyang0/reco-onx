package com.interview.minireco.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

public class Item {
    private final long itemId;
    private final String title;
    private final String source;
    private final String category;
    private double score;
    private final Map<AttrName, ItemAttr> attrs = new EnumMap<>(AttrName.class);

    public Item(long itemId, String title, String source, String category, double score) {
        this.itemId = itemId;
        this.title = title;
        this.source = source;
        this.category = category;
        this.score = score;
    }

    public long getItemId() {
        return itemId;
    }

    public String getTitle() {
        return title;
    }

    public String getSource() {
        return source;
    }

    public String getCategory() {
        return category;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public Map<AttrName, ItemAttr> getAttrs() {
        return Collections.unmodifiableMap(attrs);
    }

    public void putAttr(AttrName name, String value) {
        ItemAttr existing = attrs.get(name);
        if (existing != null) {
            existing.setValue(value);
            return;
        }
        attrs.put(name, new ItemAttr(name, value));
    }

    public Optional<String> findAttr(AttrName name) {
        return Optional.ofNullable(attrs.get(name))
                .map(ItemAttr::getValue);
    }
}
