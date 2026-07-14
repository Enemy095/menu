package com.salamu.menu.tenancy.api;

import java.util.Objects;
import java.util.UUID;

/** Stable internal identifier used to scope every tenant-owned operation. */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "value");
    }
}
