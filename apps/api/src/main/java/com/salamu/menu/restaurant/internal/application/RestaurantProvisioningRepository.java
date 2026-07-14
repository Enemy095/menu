package com.salamu.menu.restaurant.internal.application;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.restaurant.api.DefaultRestaurantSnapshot.RestaurantStatus;
import com.salamu.menu.restaurant.api.DefaultRestaurantSpec;
import com.salamu.menu.tenancy.api.TenantId;

public interface RestaurantProvisioningRepository {

    Optional<StoredRestaurant> findDefault(TenantId tenantId);

    Optional<StoredRestaurant> findDefaultForUpdate(TenantId tenantId);

    Optional<StoredRestaurant> findByNaturalKey(TenantId tenantId, String restaurantSlug);

    Optional<StoredRestaurant> findByNaturalKeyForUpdate(TenantId tenantId, String restaurantSlug);

    Optional<StoredRestaurant> findByIdForUpdate(TenantId tenantId, UUID restaurantId);

    void insertDefault(UUID restaurantId, DefaultRestaurantSpec spec, OffsetDateTime now);

    boolean deleteExactDefault(TenantId tenantId, UUID restaurantId, String restaurantSlug);

    record StoredRestaurant(
            UUID restaurantId,
            TenantId tenantId,
            String restaurantSlug,
            String displayName,
            RestaurantStatus status,
            boolean defaultRestaurant) {
    }
}
