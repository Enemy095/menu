package com.salamu.menu.restaurant.api;

import java.util.Objects;
import java.util.UUID;

import com.salamu.menu.tenancy.api.TenantId;

/** Exact proof expected before a suspended import-owned restaurant can be removed. */
public record DefaultRestaurantCleanupCandidate(
        TenantId tenantId,
        String tenantSlug,
        UUID restaurantId,
        String restaurantSlug,
        RestaurantSemanticHash expectedSemanticHash) {

    public DefaultRestaurantCleanupCandidate {
        Objects.requireNonNull(tenantId, "tenantId");
        tenantSlug = DefaultRestaurantSpec.canonicalSlug(tenantSlug, "tenantSlug");
        Objects.requireNonNull(restaurantId, "restaurantId");
        restaurantSlug = DefaultRestaurantSpec.canonicalSlug(restaurantSlug, "restaurantSlug");
        Objects.requireNonNull(expectedSemanticHash, "expectedSemanticHash");
    }
}
