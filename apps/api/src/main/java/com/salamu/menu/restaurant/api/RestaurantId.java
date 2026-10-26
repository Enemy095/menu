package com.salamu.menu.restaurant.api;

import java.util.Objects;
import java.util.UUID;

public record RestaurantId(UUID value) {

    public RestaurantId {
        Objects.requireNonNull(value, "value");
    }
}
