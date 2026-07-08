package com.interview.minireco.service.downstream.impl;

import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.service.downstream.UserFeatureService;
import com.interview.minireco.util.SimulatedLatency;

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
