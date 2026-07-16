package com.interview.minireco.service.operator.impl;

import com.interview.minireco.domain.Address;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.AbService;
import com.interview.minireco.service.downstream.AddressService;
import com.interview.minireco.service.downstream.UserFeatureService;
import com.interview.minireco.service.operator.Operator;

import java.util.List;
import java.util.Map;

public class PrepareOperator implements Operator {
    public static final String NAME = "prepare";

    private final UserFeatureService userFeatureService;
    private final AbService abService;
    private final AddressService addressService;

    public PrepareOperator(UserFeatureService userFeatureService, AbService abService, AddressService addressService) {
        this.userFeatureService = userFeatureService;
        this.abService = abService;
        this.addressService = addressService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(RecommendContext context) {
        validateScene(context.getScene());

        UserFeature userFeature = userFeatureService.getUserFeature(context.getUserId());
        Map<String, String> abParams = abService.getAbParams(context.getUserId(), context.getScene());
        Address address = addressService.getDefaultAddress(context.getUserId());

        context.setUserFeature(userFeature);
        context.setAbParams(abParams);
        context.setAddress(address);
    }

    private void validateScene(String scene) {
        if (!List.of("mall", "buy_first", "single_column", "double_column", "new_user_card").contains(scene)) {
            throw new IllegalArgumentException("unsupported scene: " + scene);
        }
    }
}
