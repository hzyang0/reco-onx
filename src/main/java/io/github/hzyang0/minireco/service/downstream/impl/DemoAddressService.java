package io.github.hzyang0.minireco.service.downstream.impl;

import io.github.hzyang0.minireco.domain.Address;
import io.github.hzyang0.minireco.service.downstream.AddressService;
import io.github.hzyang0.minireco.util.SimulatedLatency;

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
