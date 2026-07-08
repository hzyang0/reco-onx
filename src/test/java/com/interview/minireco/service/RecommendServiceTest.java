package com.interview.minireco.service;

import com.interview.minireco.domain.Address;
import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.domain.RecommendResponse;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.service.downstream.AbService;
import com.interview.minireco.service.downstream.AddressService;
import com.interview.minireco.service.downstream.MixRankService;
import com.interview.minireco.service.downstream.OnlineFeatureService;
import com.interview.minireco.service.downstream.RecallService;
import com.interview.minireco.service.downstream.UserFeatureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendServiceTest {
    @Mock
    private UserFeatureService userFeatureService;

    @Mock
    private AbService abService;

    @Mock
    private AddressService addressService;

    @Mock
    private RecallService goodsRecallService;

    @Mock
    private OnlineFeatureService onlineFeatureService;

    @Mock
    private MixRankService mixRankService;

    @Test
    void recommendShouldReturnRankedItems() {
        when(userFeatureService.getUserFeature(123L))
                .thenReturn(new UserFeature(123L, false, "digital", 25));
        when(abService.getAbParams(123L, "mall"))
                .thenReturn(Map.of("recall_exp", "A", "rank_exp", "MALL_BOOST"));
        when(addressService.getDefaultAddress(123L))
                .thenReturn(new Address("浙江", "杭州"));

        Item item1 = new Item(10001L, "手机壳", "goods", "digital", 0.8);
        Item item2 = new Item(10002L, "连衣裙", "goods", "fashion", 0.7);
        when(goodsRecallService.recall(anyMap())).thenReturn(List.of(item1, item2));

        doAnswer(invocation -> {
            List<Item> items = invocation.getArgument(0);
            for (Item item : items) {
                item.putAttr("stock", "10");
                item.putAttr("status", "ONLINE");
                item.putAttr("price", "99");
            }
            return null;
        }).when(onlineFeatureService).fillOnlineFeatures(anyList());

        when(mixRankService.rank(anyList(), anyMap(), eq(2)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RecommendService recommendService = new RecommendService(
                userFeatureService,
                abService,
                addressService,
                List.of(goodsRecallService),
                onlineFeatureService,
                mixRankService
        );

        RecommendResponse response = recommendService.recommend(new RecommendRequest(123L, "mall", 2));

        assertEquals(2, response.getItems().size());
        assertEquals("手机壳", response.getItems().get(0).getTitle());
        assertEquals(2, response.getDebug().get("returnedItemCount"));
    }

    @Test
    void recommendShouldRejectUnsupportedScene() {
        RecommendService recommendService = new RecommendService(
                userFeatureService,
                abService,
                addressService,
                List.of(goodsRecallService),
                onlineFeatureService,
                mixRankService
        );

        RecommendRequest request = new RecommendRequest(123L, "unknown_scene", 10);

        assertThrows(IllegalArgumentException.class, () -> recommendService.recommend(request));
    }
}
