package com.salamu.menu.restaurant.api;

import java.util.Objects;
import java.util.UUID;

import com.salamu.menu.tenancy.api.TenantId;

/** Sanitized provisioning inspection result. Persistence timestamps are intentionally absent. */
public record DefaultRestaurantSnapshot(
        UUID restaurantId,
        TenantId tenantId,
        String restaurantSlug,
        String displayName,
        RestaurantStatus status,
        boolean defaultRestaurant,
        RestaurantSemanticHash semanticHash) {

    public DefaultRestaurantSnapshot {
        Objects.requireNonNull(restaurantId, "restaurantId");
        Objects.requireNonNull(tenantId, "tenantId");
        restaurantSlug = DefaultRestaurantSpec.canonicalSlug(restaurantSlug, "restaurantSlug");
        displayName = DefaultRestaurantSpec.canonicalText(displayName, 160, "displayName");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(semanticHash, "semanticHash");
        if (!defaultRestaurant) {
            throw new IllegalArgumentException("Provisioning snapshot must represent the default restaurant");
        }
    }

    public enum RestaurantStatus {
        ACTIVE("active"),
        INACTIVE("inactive");

        private final String databaseValue;

        RestaurantStatus(String databaseValue) {
            this.databaseValue = databaseValue;
        }

        public String databaseValue() {
            return databaseValue;
        }

        public static RestaurantStatus fromDatabaseValue(String value) {
            for (var status : values()) {
                if (status.databaseValue.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown restaurant status");
        }
    }
}
