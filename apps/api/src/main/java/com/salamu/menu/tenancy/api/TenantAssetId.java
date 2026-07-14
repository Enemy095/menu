package com.salamu.menu.tenancy.api;

import java.util.Objects;
import java.util.UUID;

/** Stable internal identifier of one immutable tenant asset record. */
public record TenantAssetId(UUID value) {

    public TenantAssetId {
        Objects.requireNonNull(value, "value");
    }
}
