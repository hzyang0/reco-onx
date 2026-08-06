package io.github.hzyang0.minireco.service;

import io.github.hzyang0.minireco.service.data.DatabaseConfig;
import io.github.hzyang0.minireco.service.data.JdbcDataRepository;
import io.github.hzyang0.minireco.service.downstream.RecallService;
import io.github.hzyang0.minireco.service.downstream.impl.JdbcAbService;
import io.github.hzyang0.minireco.service.downstream.impl.JdbcAddressService;
import io.github.hzyang0.minireco.service.downstream.impl.JdbcOnlineFeatureService;
import io.github.hzyang0.minireco.service.downstream.impl.JdbcRecallService;
import io.github.hzyang0.minireco.service.downstream.impl.JdbcUserFeatureService;
import io.github.hzyang0.minireco.service.downstream.impl.RuleBasedMixRankService;
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

/** Builds the production object graph used by the HTTP application. */
public final class ApplicationWiring {
    private ApplicationWiring() {
    }

    public static RecommendationFacade createRecommendService() {
        return createRecommendService(createRepository());
    }

    public static JdbcDataRepository createRepository() {
        JdbcDataRepository repository = new JdbcDataRepository(DatabaseConfig.fromEnvironment());
        repository.verifyConnection();
        return repository;
    }

    public static RecommendationFacade createRecommendService(JdbcDataRepository repository) {
        List<RecallService> recallServices = List.of(
                new JdbcRecallService("goods", 20, repository),
                new JdbcRecallService("live", 20, repository),
                new JdbcRecallService("ad", 20, repository)
        );
        Operator prepare = new PrepareOperator(
                new JdbcUserFeatureService(repository),
                new JdbcAbService(repository),
                new JdbcAddressService(repository)
        );
        Operator recall = new RecallOperator(recallServices);
        Operator onlineFeature = new OnlineFeatureOperator(new JdbcOnlineFeatureService(repository));
        Operator mixRank = new MixRankOperator(new RuleBasedMixRankService());
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
