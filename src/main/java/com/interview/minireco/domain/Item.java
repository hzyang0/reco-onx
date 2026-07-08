package com.interview.minireco.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Item {
    private final long itemId;
    private final String title;
    private final String source;
    private final String category;
    private double score;
    private final List<ItemAttr> attrs = new ArrayList<>();

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

    public List<ItemAttr> getAttrs() {
        return attrs;
    }

    public void putAttr(String name, String value) {
        for (ItemAttr attr : attrs) {
            if (attr.getName().equals(name)) {
                attr.setValue(value);
                return;
            }
        }
        attrs.add(new ItemAttr(name, value));
    }

    public Optional<String> findAttr(String name) {
        for (ItemAttr attr : attrs) {
            if (attr.getName().equals(name)) {
                return Optional.ofNullable(attr.getValue());
            }
        }
        return Optional.empty();
    }
}
