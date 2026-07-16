package com.interview.minireco.service.operator.impl;

import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.OnlineFeatureService;
import com.interview.minireco.service.operator.Operator;

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
