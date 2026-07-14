package com.salamu.menu.restaurant.internal.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface RestaurantJpaRepository extends Repository<RestaurantEntity, UUID> {

    Optional<RestaurantEntity> findByTenantIdAndId(UUID tenantId, UUID restaurantId);

    Optional<RestaurantEntity> findByTenantIdAndSlug(UUID tenantId, String slug);

    Optional<RestaurantEntity> findByTenantIdAndDefaultRestaurantTrue(UUID tenantId);
}
