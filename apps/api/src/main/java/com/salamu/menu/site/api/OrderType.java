package com.salamu.menu.site.api;

public enum OrderType {
    PREORDER("preorder"),
    DELIVERY("delivery"),
    PICKUP("pickup");

    private final String contractValue;

    OrderType(String contractValue) {
        this.contractValue = contractValue;
    }

    public String contractValue() {
        return contractValue;
    }
}
