package com.salamu.menu.menu.internal.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface MenuVersionJpaRepository extends Repository<MenuVersionEntity, UUID> {

    Optional<MenuVersionEntity> findByTenantIdAndRestaurantIdAndStatus(
            UUID tenantId,
            UUID restaurantId,
            String status);

    Optional<MenuVersionEntity> findByTenantIdAndRestaurantIdAndVersion(
            UUID tenantId,
            UUID restaurantId,
            long version);
}

interface MenuCategoryJpaRepository extends Repository<MenuCategoryEntity, UUID> {

    List<MenuCategoryEntity> findAllByTenantIdAndMenuVersionIdOrderBySortOrder(
            UUID tenantId,
            UUID menuVersionId);
}

interface MenuItemJpaRepository extends Repository<MenuItemEntity, UUID> {

    Optional<MenuItemEntity> findByTenantIdAndMenuVersionIdAndPublicId(
            UUID tenantId,
            UUID menuVersionId,
            String publicId);

    List<MenuItemEntity> findAllByTenantIdAndMenuVersionIdOrderBySortOrder(
            UUID tenantId,
            UUID menuVersionId);
}
