package com.salamu.menu.site.api;

import java.util.Objects;

public record AvailableOrderType(OrderType code, String label, boolean isDefault) {

    public AvailableOrderType {
        Objects.requireNonNull(code, "code");
        label = SiteValues.required(label, 1, 80, "label");
    }
}
