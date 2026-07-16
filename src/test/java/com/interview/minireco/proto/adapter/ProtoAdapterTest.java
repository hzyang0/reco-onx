package com.interview.minireco.proto.adapter;

import com.google.protobuf.CodedOutputStream;
import com.interview.minireco.domain.AttrName;
import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.RecommendResponse;
import com.interview.minireco.proto.ad.AdExtensionPb;
import com.interview.minireco.proto.ad.AdRecallItemPb;
import com.interview.minireco.proto.ad.AdRecallResponsePb;
import com.interview.minireco.proto.goods.GoodsAttributePb;
import com.interview.minireco.proto.goods.GoodsRecallItemPb;
import com.interview.minireco.proto.goods.GoodsRecallResponsePb;
import com.interview.minireco.proto.internal.InternalItemPb;
import com.interview.minireco.proto.internal.InternalRecallResultPb;
import com.interview.minireco.proto.live.LiveFeaturePb;
import com.interview.minireco.proto.live.LiveRecallItemPb;
import com.interview.minireco.proto.live.LiveRecallResponsePb;
import com.interview.minireco.proto.upstream.UpstreamRecommendResponsePb;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtoAdapterTest {
    @Test
    void goodsAdapterShouldConvergeListAttrsToMapAndUseLastDuplicateValue() {
        GoodsRecallItemPb item = GoodsRecallItemPb.newBuilder()
                .setGoodsId(101L)
                .setGoodsTitle("Phone")
                .setCategory("digital")
                .setRelevanceScore(0.91)
                .addAttributes(attr("recall_reason", "old"))
                .addAttributes(attr("", "ignored"))
                .addAttributes(attr("recall_reason", "new"))
                .build();

        InternalItemPb converted = GoodsRecallProtoAdapter.toInternal(
                GoodsRecallResponsePb.newBuilder().addItems(item).build()
        ).getItems(0);

        assertEquals(101L, converted.getItemId());
        assertEquals("goods", converted.getSource());
        assertEquals("new", converted.getAttrsOrThrow("recall_reason"));
        assertEquals(1, converted.getAttrsCount());
    }

    @Test
    void liveAdapterShouldMapProductAsItemAndKeepRoomId() {
        LiveRecallItemPb item = LiveRecallItemPb.newBuilder()
                .setRoomId(7001L)
                .setProductId(202L)
                .setRoomTitle("Live phone")
                .setProductCategory("digital")
                .setPredictionScore(0.73f)
                .addFeatures(LiveFeaturePb.newBuilder()
                        .setFeatureKey("recall_reason")
                        .setFeatureValue("live_hot"))
                .build();

        InternalItemPb converted = LiveRecallProtoAdapter.toInternal(
                LiveRecallResponsePb.newBuilder().addItems(item).build()
        ).getItems(0);

        assertEquals(202L, converted.getItemId());
        assertEquals("7001", converted.getAttrsOrThrow("room_id"));
        assertEquals(0.73, converted.getScore(), 0.000_001);
    }

    @Test
    void adAdapterShouldConvertFixedPointScoreAndKeepCreativeId() {
        AdRecallItemPb item = AdRecallItemPb.newBuilder()
                .setCreativeId(8001L)
                .setPromotedGoodsId(303L)
                .setCopywriting("Sponsored phone")
                .setIndustry("digital")
                .setScoreMicros(625_000L)
                .addExtensions(AdExtensionPb.newBuilder()
                        .setExtensionName("recall_reason")
                        .setExtensionValue("commercial"))
                .build();

        InternalItemPb converted = AdRecallProtoAdapter.toInternal(
                AdRecallResponsePb.newBuilder().addItems(item).build()
        ).getItems(0);

        assertEquals(303L, converted.getItemId());
        assertEquals(0.625, converted.getScore());
        assertEquals("8001", converted.getAttrsOrThrow("creative_id"));
    }

    @Test
    void domainAdapterShouldMapRegisteredAttrsAndIgnoreFutureAttrs() {
        InternalItemPb source = InternalItemPb.newBuilder()
                .setItemId(404L)
                .setTitle("Future compatible item")
                .setSource("goods")
                .setCategory("digital")
                .setScore(0.8)
                .putAttrs("recall_reason", "preferred_category")
                .putAttrs("future_attr", "safe-to-ignore")
                .build();

        Item converted = InternalItemDomainAdapter.toDomain(
                InternalRecallResultPb.newBuilder().addItems(source).build()
        ).get(0);

        assertEquals("preferred_category", converted.findAttr(AttrName.RECALL_REASON).orElseThrow());
        assertEquals(1, converted.getAttrs().size());
        assertFalse(AttrName.fromKey("future_attr").isPresent());
    }

    @Test
    void protobufParserShouldKeepKnownDataWhenNewUnknownFieldArrives() throws Exception {
        InternalItemPb original = InternalItemPb.newBuilder()
                .setItemId(505L)
                .setTitle("Compatible")
                .build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        original.writeTo(bytes);
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeString(99, "field-added-by-new-server");
        output.flush();

        InternalItemPb parsedByOldClient = InternalItemPb.parseFrom(bytes.toByteArray());

        assertEquals(505L, parsedByOldClient.getItemId());
        assertEquals("Compatible", parsedByOldClient.getTitle());
        assertTrue(parsedByOldClient.getUnknownFields().hasField(99));
    }

    @Test
    void upstreamResponseShouldSurviveBinaryRoundTrip() throws Exception {
        Item item = new Item(606L, "Ranked phone", "goods", "digital", 0.96);
        item.putAttr(AttrName.PRICE, "3999");
        RecommendResponse source = new RecommendResponse(
                "request-pb",
                123L,
                "mall",
                37L,
                List.of(item),
                Map.of()
        );

        byte[] bytes = UpstreamRecommendProtoAdapter.fromDomain(source).toByteArray();
        UpstreamRecommendResponsePb decoded = UpstreamRecommendResponsePb.parseFrom(bytes);

        assertEquals("request-pb", decoded.getRequestId());
        assertEquals(1, decoded.getItemsCount());
        assertEquals("digital", decoded.getItems(0).getAttributesOrThrow("category"));
        assertEquals("3999", decoded.getItems(0).getAttributesOrThrow("price"));
    }

    private GoodsAttributePb attr(String name, String value) {
        return GoodsAttributePb.newBuilder().setName(name).setValue(value).build();
    }
}
