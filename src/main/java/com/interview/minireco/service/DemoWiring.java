package com.interview.minireco.service;

import com.interview.minireco.degradation.DegradationManager;
import com.interview.minireco.migration.ComparisonRegistry;
import com.interview.minireco.migration.MigrationRecommendationFacade;
import com.interview.minireco.migration.RecommendationDiffEngine;
import com.interview.minireco.migration.RolloutManager;
import com.interview.minireco.resilience.FaultInjectingRecallService;
import com.interview.minireco.resilience.FaultInjectionManager;
import com.interview.minireco.resilience.ResilienceConfig;
import com.interview.minireco.resilience.ResilienceRegistry;
import com.interview.minireco.resilience.ResilientRecallService;
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
import com.interview.minireco.service.operator.impl.SequentialRecallOperator;

import java.util.List;

public final class DemoWiring {
    private DemoWiring() {
    }

    public static RecommendService createRecommendService() {
        return createPipeline(true, true);
    }

    public static RecommendService createLegacyRecommendService() {
        return createPipeline(false, false);
    }

    public static RecommendationFacade createRoutedRecommendService() {
        RecommendService primaryLegacyPipeline = createPipeline(false, false);
        RecommendService primaryNewPipeline = createPipeline(true, true);
        RecommendService shadowLegacyPipeline = createPipeline(false, false);
        RecommendService shadowNewPipeline = createPipeline(true, false);
        return new MigrationRecommendationFacade(
                primaryLegacyPipeline,
                primaryNewPipeline,
                shadowLegacyPipeline,
                shadowNewPipeline,
                RolloutManager.global(),
                new RecommendationDiffEngine(),
                ComparisonRegistry.global()
        );
    }

    private static RecommendService createPipeline(boolean parallelRecall, boolean registerResilience) {
        DemoUserFeatureService userFeatureService = new DemoUserFeatureService();
        DemoAbService abService = new DemoAbService();
        DemoAddressService addressService = new DemoAddressService();
        DemoOnlineFeatureService onlineFeatureService = new DemoOnlineFeatureService();
        DemoMixRankService mixRankService = new DemoMixRankService();
        List<RecallService> recallServices = createRecallServices(registerResilience);

        Operator prepareOperator = new PrepareOperator(userFeatureService, abService, addressService);
        Operator degradationOperator = new DegradationOperator(DegradationManager.global());
        Operator recallOperator = parallelRecall
                ? new RecallOperator(recallServices)
                : new SequentialRecallOperator(recallServices);
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

    private static List<RecallService> createRecallServices(boolean registerResilience) {
        List<RecallService> rawRecallServices = List.of(
                new GoodsRecallService(),
                new LiveRecallService(),
                new AdRecallService()
        );
        FaultInjectionManager faultInjectionManager = FaultInjectionManager.global();
        ResilienceConfig resilienceConfig = ResilienceConfig.recallDefaults();

        return rawRecallServices.stream()
                .map(service -> new FaultInjectingRecallService(service, faultInjectionManager))
                .map(service -> new ResilientRecallService(service, resilienceConfig))
                .map(service -> registerResilience
                        ? ResilienceRegistry.global().register(service)
                        : service)
                .toList();
    }
}
