package com.interview.minireco.proto.adapter;

import com.interview.minireco.proto.internal.InternalItemPb;

import java.util.function.BiConsumer;

final class ProtoAttrMapper {
    private ProtoAttrMapper() {
    }

    static void putIfValid(InternalItemPb.Builder builder, String key, String value) {
        putIfValid(builder::putAttrs, key, value);
    }

    static void putIfValid(BiConsumer<String, String> consumer, String key, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        consumer.accept(key, value == null ? "" : value);
    }
}
