package com.salamu.menu.menu.api;

import java.util.Objects;
import java.util.UUID;

import com.salamu.menu.tenancy.api.TenantId;

/** Exact expected identity and semantic proof for database-first menu cleanup. */
public record MenuCleanupCandidate(
        TenantId tenantId,
        UUID restaurantId,
        long version,
        MenuSemanticHash expectedSemanticHash) {

    public MenuCleanupCandidate {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(restaurantId, "restaurantId");
        Objects.requireNonNull(expectedSemanticHash, "expectedSemanticHash");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
    }
}
