package io.github.hzyang0.minireco.service;

import io.github.hzyang0.minireco.domain.AttrName;
import io.github.hzyang0.minireco.domain.Address;
import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.domain.RecommendRequest;
import io.github.hzyang0.minireco.domain.RecommendResponse;
import io.github.hzyang0.minireco.domain.UserFeature;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.downstream.AbService;
import io.github.hzyang0.minireco.service.downstream.AddressService;
import io.github.hzyang0.minireco.service.downstream.MixRankService;
import io.github.hzyang0.minireco.service.downstream.OnlineFeatureService;
import io.github.hzyang0.minireco.service.downstream.RecallService;
import io.github.hzyang0.minireco.service.downstream.UserFeatureService;
import io.github.hzyang0.minireco.service.operator.Operator;
import io.github.hzyang0.minireco.service.operator.OperatorConfig;
import io.github.hzyang0.minireco.service.operator.graph.DagGraph;
import io.github.hzyang0.minireco.service.operator.graph.DagNode;
import io.github.hzyang0.minireco.service.operator.graph.ParallelDagOperatorExecutor;
import io.github.hzyang0.minireco.service.operator.impl.FilterOperator;
import io.github.hzyang0.minireco.service.operator.impl.MixRankOperator;
import io.github.hzyang0.minireco.service.operator.impl.OnlineFeatureOperator;
import io.github.hzyang0.minireco.service.operator.impl.PostProcessOperator;
import io.github.hzyang0.minireco.service.operator.impl.PrepareOperator;
import io.github.hzyang0.minireco.service.operator.impl.RecallOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
                .thenReturn(new Address("Zhejiang", "Hangzhou"));

        Item item1 = new Item(10001L, "Phone case", "goods", "digital", 0.8);
        Item item2 = new Item(10002L, "Dress", "goods", "fashion", 0.7);
        when(goodsRecallService.recall(any(RecommendContext.class))).thenReturn(List.of(item1, item2));

        doAnswer(invocation -> {
            List<Item> items = invocation.getArgument(0);
            for (Item item : items) {
                item.putAttr(AttrName.STOCK, "10");
                item.putAttr(AttrName.STATUS, "ONLINE");
                item.putAttr(AttrName.PRICE, "99");
            }
            return null;
        }).when(onlineFeatureService).fillOnlineFeatures(anyList());

        when(mixRankService.rank(anyList(), any(RecommendContext.class), eq(10)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RecommendService recommendService = createRecommendService();

        RecommendResponse response = recommendService.recommend(new RecommendRequest(123L, "mall", 2));

        assertEquals(2, response.getItems().size());
        assertEquals("Phone case", response.getItems().get(0).getTitle());
        assertEquals("10", response.getItems().get(0).findAttr(AttrName.STOCK).orElseThrow());
        assertEquals(2, response.getDebug().get("returnedItemCount"));
    }

    @Test
    void recommendShouldRejectUnsupportedScene() {
        RecommendService recommendService = createRecommendService();

        RecommendRequest request = new RecommendRequest(123L, "unknown_scene", 10);

        assertThrows(IllegalArgumentException.class, () -> recommendService.recommend(request));
    }

    private RecommendService createRecommendService() {
        when(goodsRecallService.source()).thenReturn("goods");
        List<Operator> operators = List.of(
                new PrepareOperator(userFeatureService, abService, addressService),
                new RecallOperator(List.of(goodsRecallService)),
                new OnlineFeatureOperator(onlineFeatureService),
                new FilterOperator(),
                new MixRankOperator(mixRankService),
                new PostProcessOperator()
        );
        List<OperatorConfig> configs = List.of(
                OperatorConfig.enabled(PrepareOperator.NAME),
                OperatorConfig.enabled(RecallOperator.NAME),
                OperatorConfig.enabled(OnlineFeatureOperator.NAME),
                OperatorConfig.enabled(FilterOperator.NAME),
                OperatorConfig.enabled(MixRankOperator.NAME),
                OperatorConfig.enabled(PostProcessOperator.NAME)
        );
        DagGraph graph = new DagGraph(List.of(
                DagNode.of(operators.get(0)),
                DagNode.of(operators.get(1), PrepareOperator.NAME),
                DagNode.of(operators.get(2), RecallOperator.NAME),
                DagNode.of(operators.get(4), RecallOperator.NAME),
                DagNode.of(operators.get(3), OnlineFeatureOperator.NAME, MixRankOperator.NAME),
                DagNode.of(operators.get(5), FilterOperator.NAME)
        ));
        return new RecommendService(new ParallelDagOperatorExecutor(graph, configs, 4));
    }
}
