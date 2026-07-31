package io.github.hzyang0.minireco.service;

import io.github.hzyang0.minireco.service.downstream.RecallService;
import io.github.hzyang0.minireco.service.downstream.impl.AdRecallService;
import io.github.hzyang0.minireco.service.downstream.impl.DemoAbService;
import io.github.hzyang0.minireco.service.downstream.impl.DemoAddressService;
import io.github.hzyang0.minireco.service.downstream.impl.DemoMixRankService;
import io.github.hzyang0.minireco.service.downstream.impl.DemoOnlineFeatureService;
import io.github.hzyang0.minireco.service.downstream.impl.DemoUserFeatureService;
import io.github.hzyang0.minireco.service.downstream.impl.GoodsRecallService;
import io.github.hzyang0.minireco.service.downstream.impl.LiveRecallService;
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

import java.util.List;

public final class DemoWiring {
    private DemoWiring() {
    }

    public static RecommendationFacade createRecommendService() {
        List<RecallService> recallServices = List.of(
                new GoodsRecallService(),
                new LiveRecallService(),
                new AdRecallService()
        );

        Operator prepare = new PrepareOperator(
                new DemoUserFeatureService(),
                new DemoAbService(),
                new DemoAddressService()
        );
        Operator recall = new RecallOperator(recallServices);
        Operator onlineFeature = new OnlineFeatureOperator(new DemoOnlineFeatureService());
        Operator mixRank = new MixRankOperator(new DemoMixRankService());
        Operator filter = new FilterOperator();
        Operator postProcess = new PostProcessOperator();

        List<OperatorConfig> configs = List.of(
                OperatorConfig.enabled(PrepareOperator.NAME),
                OperatorConfig.enabled(RecallOperator.NAME),
                OperatorConfig.enabled(OnlineFeatureOperator.NAME),
                OperatorConfig.enabled(MixRankOperator.NAME),
                OperatorConfig.enabled(FilterOperator.NAME),
                OperatorConfig.enabled(PostProcessOperator.NAME)
        );

        DagGraph graph = new DagGraph(List.of(
                DagNode.of(prepare),
                DagNode.of(recall, PrepareOperator.NAME),
                DagNode.of(onlineFeature, RecallOperator.NAME),
                DagNode.of(mixRank, RecallOperator.NAME),
                DagNode.of(filter, OnlineFeatureOperator.NAME, MixRankOperator.NAME),
                DagNode.of(postProcess, FilterOperator.NAME)
        ));

        return new RecommendService(new ParallelDagOperatorExecutor(graph, configs, 4));
    }
}
