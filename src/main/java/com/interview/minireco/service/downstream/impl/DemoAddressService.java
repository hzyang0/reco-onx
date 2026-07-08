package com.interview.minireco.service.downstream.impl;

import com.interview.minireco.domain.Address;
import com.interview.minireco.service.downstream.AddressService;
import com.interview.minireco.util.SimulatedLatency;

public class DemoAddressService implements AddressService {
    @Override
    public Address getDefaultAddress(long userId) {
        SimulatedLatency.sleepMs(10);
        if (userId % 3 == 0) {
            return new Address("浙江", "杭州");
        }
        if (userId % 3 == 1) {
            return new Address("广东", "广州");
        }
        return new Address("上海", "上海");
    }
}
