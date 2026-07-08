package com.interview.minireco.service.downstream.impl;

import com.interview.minireco.service.downstream.AbService;
import com.interview.minireco.util.SimulatedLatency;

import java.util.HashMap;
import java.util.Map;

public class DemoAbService implements AbService {
    @Override
    public Map<String, String> getAbParams(long userId, String scene) {
        SimulatedLatency.sleepMs(10);
        Map<String, String> params = new HashMap<>();
        params.put("recall_exp", userId % 2 == 0 ? "B" : "A");
        params.put("rank_exp", "mall".equals(scene) ? "MALL_BOOST" : "DEFAULT");
        return params;
    }
}
