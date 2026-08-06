package io.github.hzyang0.minireco.service.operator.impl;

import io.github.hzyang0.minireco.domain.Address;
import io.github.hzyang0.minireco.domain.UserFeature;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.downstream.AbService;
import io.github.hzyang0.minireco.service.downstream.AddressService;
import io.github.hzyang0.minireco.service.downstream.UserFeatureService;
import io.github.hzyang0.minireco.service.operator.Operator;

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
        if (!List.of("mall", "video_feed", "buy_first").contains(scene)) {
            throw new IllegalArgumentException("unsupported scene: " + scene);
        }
    }
}
