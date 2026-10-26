package com.salamu.menu.menu.api;

import java.util.Objects;
import java.util.UUID;

public record MenuVersionId(UUID value) {

    public MenuVersionId {
        Objects.requireNonNull(value, "value");
    }
}
