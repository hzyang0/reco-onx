package com.interview.minireco.util;

import com.interview.minireco.domain.AttrName;
import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.ItemAttr;
import com.interview.minireco.domain.RecommendResponse;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class JsonUtil {
    private JsonUtil() {
    }

    public static String responseToJson(RecommendResponse response) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        appendField(json, "requestId", response.getRequestId()).append(",");
        appendField(json, "userId", response.getUserId()).append(",");
        appendField(json, "scene", response.getScene()).append(",");
        appendField(json, "costMs", response.getCostMs()).append(",");
        json.append("\"items\":").append(itemsToJson(response.getItems())).append(",");
        json.append("\"debug\":").append(mapToJson(response.getDebug()));
        json.append("}");
        return json.toString();
    }

    public static String errorToJson(String message) {
        return "{\"error\":\"" + escape(message) + "\"}";
    }

    public static String mapToJson(Map<?, ?> map) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<?, ?> entry = iterator.next();
            json.append("\"").append(escape(String.valueOf(entry.getKey()))).append("\":");
            appendValue(json, entry.getValue());
            if (iterator.hasNext()) {
                json.append(",");
            }
        }
        json.append("}");
        return json.toString();
    }

    private static String itemsToJson(List<Item> items) {
        StringBuilder json = new StringBuilder();
        json.append("[");
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            json.append("{");
            appendField(json, "itemId", item.getItemId()).append(",");
            appendField(json, "title", item.getTitle()).append(",");
            appendField(json, "source", item.getSource()).append(",");
            appendField(json, "category", item.getCategory()).append(",");
            appendField(json, "score", round(item.getScore())).append(",");
            json.append("\"attrs\":").append(attrsToJson(item.getAttrs()));
            json.append("}");
            if (i < items.size() - 1) {
                json.append(",");
            }
        }
        json.append("]");
        return json.toString();
    }

    private static String attrsToJson(Map<AttrName, ItemAttr> attrs) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        Iterator<Map.Entry<AttrName, ItemAttr>> iterator = attrs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AttrName, ItemAttr> entry = iterator.next();
            ItemAttr attr = entry.getValue();
            appendField(json, entry.getKey().key(), attr.getValue());
            if (iterator.hasNext()) {
                json.append(",");
            }
        }
        json.append("}");
        return json.toString();
    }

    private static StringBuilder appendField(StringBuilder json, String name, String value) {
        return json.append("\"")
                .append(escape(name))
                .append("\":\"")
                .append(escape(value))
                .append("\"");
    }

    private static StringBuilder appendField(StringBuilder json, String name, long value) {
        return json.append("\"")
                .append(escape(name))
                .append("\":")
                .append(value);
    }

    private static StringBuilder appendField(StringBuilder json, String name, double value) {
        return json.append("\"")
                .append(escape(name))
                .append("\":")
                .append(value);
    }

    private static void appendValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
        } else if (value instanceof Map<?, ?> nestedMap) {
            json.append(mapToJson(nestedMap));
        } else if (value instanceof Iterable<?> iterable) {
            appendIterable(json, iterable);
        } else {
            json.append("\"").append(escape(String.valueOf(value))).append("\"");
        }
    }

    private static void appendIterable(StringBuilder json, Iterable<?> iterable) {
        json.append("[");
        Iterator<?> iterator = iterable.iterator();
        while (iterator.hasNext()) {
            appendValue(json, iterator.next());
            if (iterator.hasNext()) {
                json.append(",");
            }
        }
        json.append("]");
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
