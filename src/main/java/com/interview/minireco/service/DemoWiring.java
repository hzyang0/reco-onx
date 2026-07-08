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

import java.util.List;

public final class DemoWiring {
    private DemoWiring() {
    }

    public static RecommendService createRecommendService() {
        List<RecallService> recallServices = List.of(
                new GoodsRecallService(),
                new LiveRecallService(),
                new AdRecallService()
        );
        return new RecommendService(
                new DemoUserFeatureService(),
                new DemoAbService(),
                new DemoAddressService(),
                recallServices,
                new DemoOnlineFeatureService(),
                new DemoMixRankService()
        );
    }
}
