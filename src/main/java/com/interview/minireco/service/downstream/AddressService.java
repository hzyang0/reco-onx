package com.interview.minireco.service.downstream;

import com.interview.minireco.domain.Address;

public interface AddressService {
    Address getDefaultAddress(long userId);
}
