package io.github.hzyang0.minireco.service.operator.impl;

import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.downstream.OnlineFeatureService;
import io.github.hzyang0.minireco.service.operator.Operator;

public class OnlineFeatureOperator implements Operator {
    public static final String NAME = "onlineFeature";

    private final OnlineFeatureService onlineFeatureService;

    public OnlineFeatureOperator(OnlineFeatureService onlineFeatureService) {
        this.onlineFeatureService = onlineFeatureService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(RecommendContext context) {
        onlineFeatureService.fillOnlineFeatures(context.getRecalledItems());
    }
}
