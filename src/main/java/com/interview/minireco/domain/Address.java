package com.interview.minireco.domain;

public class Address {
    private final String province;
    private final String city;

    public Address(String province, String city) {
        this.province = province;
        this.city = city;
    }

    public String getProvince() {
        return province;
    }

    public String getCity() {
        return city;
    }
}
