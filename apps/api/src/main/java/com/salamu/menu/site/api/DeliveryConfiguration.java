package com.salamu.menu.site.api;

import java.util.Objects;

public record DeliveryConfiguration(
        boolean enabled,
        boolean addressRequired,
        DeliveryFeePolicy feePolicy) {

    public DeliveryConfiguration {
        Objects.requireNonNull(feePolicy, "feePolicy");
        if (!addressRequired) {
            throw new IllegalArgumentException("The accepted delivery contract requires an address");
        }
    }
}
