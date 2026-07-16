package com.interview.minireco.proto;

import com.interview.minireco.proto.upstream.UpstreamRecommendItemPb;
import com.interview.minireco.proto.upstream.UpstreamRecommendResponsePb;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ProtoResponseDecoder {
    private ProtoResponseDecoder() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: ProtoResponseDecoder <response.pb>");
        }
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        UpstreamRecommendResponsePb response = UpstreamRecommendResponsePb.parseFrom(bytes);
        System.out.printf(
                "requestId=%s userId=%d scene=%s costMs=%d itemCount=%d bytes=%d%n",
                response.getRequestId(),
                response.getUserId(),
                response.getScene(),
                response.getCostMs(),
                response.getItemsCount(),
                bytes.length
        );
        for (int i = 0; i < Math.min(3, response.getItemsCount()); i++) {
            UpstreamRecommendItemPb item = response.getItems(i);
            System.out.printf(
                    "item[%d]: id=%d type=%s score=%.4f category=%s title=%s%n",
                    i,
                    item.getId(),
                    item.getItemType(),
                    item.getRankScore(),
                    item.getAttributesOrDefault("category", ""),
                    item.getDisplayTitle()
            );
        }
    }
}
