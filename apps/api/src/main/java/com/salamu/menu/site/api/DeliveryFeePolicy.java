package com.salamu.menu.site.api;

public enum DeliveryFeePolicy {
    NONE("none");

    private final String contractValue;

    DeliveryFeePolicy(String contractValue) {
        this.contractValue = contractValue;
    }

    public String contractValue() {
        return contractValue;
    }
}
