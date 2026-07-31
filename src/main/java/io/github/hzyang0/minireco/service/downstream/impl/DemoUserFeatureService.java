package io.github.hzyang0.minireco.service.downstream.impl;

import io.github.hzyang0.minireco.domain.UserFeature;
import io.github.hzyang0.minireco.service.downstream.UserFeatureService;
import io.github.hzyang0.minireco.util.SimulatedLatency;

public class DemoUserFeatureService implements UserFeatureService {
    @Override
    public UserFeature getUserFeature(long userId) {
        SimulatedLatency.sleepMs(15);
        boolean newUser = userId % 5 == 0;
        String preferredCategory = switch ((int) (userId % 4)) {
            case 0 -> "digital";
            case 1 -> "fashion";
            case 2 -> "food";
            default -> "home";
        };
        int age = 18 + (int) (userId % 30);
        return new UserFeature(userId, newUser, preferredCategory, age);
    }
}
