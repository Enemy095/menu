package com.salamu.menu.menu.api;

import java.util.Objects;
import java.util.UUID;

public record MenuItemId(UUID value) {

    public MenuItemId {
        Objects.requireNonNull(value, "value");
    }
}
