package io.github.hzyang0.minireco.service.downstream.impl;

import io.github.hzyang0.minireco.domain.Address;
import io.github.hzyang0.minireco.service.data.JdbcDataRepository;
import io.github.hzyang0.minireco.service.downstream.AddressService;

public final class JdbcAddressService implements AddressService {
    private final JdbcDataRepository repository;

    public JdbcAddressService(JdbcDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public Address getDefaultAddress(long userId) {
        return repository.findUser(userId)
                .map(profile -> new Address(profile.province(), profile.city()))
                .orElse(new Address("未知", "未知"));
    }
}
