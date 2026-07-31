package io.github.hzyang0.minireco.service.downstream;

import io.github.hzyang0.minireco.domain.Address;

public interface AddressService {
    Address getDefaultAddress(long userId);
}
