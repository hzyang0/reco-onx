package com.interview.minireco.service;

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
import com.interview.minireco.service.operator.OperatorExecutor;
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

        List<Operator> operators = List.of(
                new PrepareOperator(userFeatureService, abService, addressService),
                new RecallOperator(recallServices),
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

        return new RecommendService(new OperatorExecutor(operators, configs));
    }
}
