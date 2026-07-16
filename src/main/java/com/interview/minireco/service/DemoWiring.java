package com.interview.minireco.service;

import com.interview.minireco.degradation.DegradationManager;
import com.interview.minireco.service.downstream.RecallService;
import com.interview.minireco.service.downstream.impl.AdRecallService;
import com.interview.minireco.service.downstream.impl.DemoAbService;
import com.interview.minireco.service.downstream.impl.DemoAddressService;
import com.interview.minireco.service.downstream.impl.DemoMixRankService;
import com.interview.minireco.service.downstream.impl.DemoOnlineFeatureService;
import com.interview.minireco.service.downstream.impl.DemoUserFeatureService;
import com.interview.minireco.service.downstream.impl.GoodsRecallService;
import com.interview.minireco.service.downstream.impl.LiveRecallService;
import com.interview.minireco.service.operator.Operator;
import com.interview.minireco.service.operator.OperatorConfig;
import com.interview.minireco.service.operator.graph.DagGraph;
import com.interview.minireco.service.operator.graph.DagNode;
import com.interview.minireco.service.operator.graph.ParallelDagOperatorExecutor;
import com.interview.minireco.service.operator.impl.DegradationOperator;
import com.interview.minireco.service.operator.impl.FilterOperator;
import com.interview.minireco.service.operator.impl.MixRankOperator;
import com.interview.minireco.service.operator.impl.OnlineFeatureOperator;
import com.interview.minireco.service.operator.impl.PostProcessOperator;
import com.interview.minireco.service.operator.impl.PrepareOperator;
import com.interview.minireco.service.operator.impl.RecallOperator;

import java.util.List;

public final class DemoWiring {
    private DemoWiring() {
    }

    public static RecommendService createRecommendService() {
        DemoUserFeatureService userFeatureService = new DemoUserFeatureService();
        DemoAbService abService = new DemoAbService();
        DemoAddressService addressService = new DemoAddressService();
        DemoOnlineFeatureService onlineFeatureService = new DemoOnlineFeatureService();
        DemoMixRankService mixRankService = new DemoMixRankService();

        List<RecallService> recallServices = List.of(
                new GoodsRecallService(),
                new LiveRecallService(),
                new AdRecallService()
        );

        Operator prepareOperator = new PrepareOperator(userFeatureService, abService, addressService);
        Operator degradationOperator = new DegradationOperator(DegradationManager.global());
        Operator recallOperator = new RecallOperator(recallServices);
        Operator onlineFeatureOperator = new OnlineFeatureOperator(onlineFeatureService);
        Operator filterOperator = new FilterOperator();
        Operator mixRankOperator = new MixRankOperator(mixRankService);
        Operator postProcessOperator = new PostProcessOperator();

        List<OperatorConfig> configs = List.of(
                OperatorConfig.enabled(PrepareOperator.NAME),
                OperatorConfig.enabled(DegradationOperator.NAME),
                OperatorConfig.enabled(RecallOperator.NAME),
                OperatorConfig.enabled(OnlineFeatureOperator.NAME),
                OperatorConfig.enabled(FilterOperator.NAME),
                OperatorConfig.enabled(MixRankOperator.NAME),
                OperatorConfig.enabled(PostProcessOperator.NAME)
        );

        DagGraph graph = new DagGraph(List.of(
                DagNode.of(prepareOperator),
                DagNode.of(degradationOperator, PrepareOperator.NAME),
                DagNode.of(recallOperator, DegradationOperator.NAME),
                DagNode.of(onlineFeatureOperator, RecallOperator.NAME),
                DagNode.of(mixRankOperator, RecallOperator.NAME),
                DagNode.of(filterOperator, OnlineFeatureOperator.NAME, MixRankOperator.NAME),
                DagNode.of(postProcessOperator, FilterOperator.NAME)
        ));

        return new RecommendService(new ParallelDagOperatorExecutor(graph, configs, 4));
    }
}
