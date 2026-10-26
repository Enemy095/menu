package com.salamu.menu.asset.api;

import java.util.Objects;
import java.util.UUID;

public record AssetId(UUID value) {

    public AssetId {
        Objects.requireNonNull(value, "value");
    }
}
