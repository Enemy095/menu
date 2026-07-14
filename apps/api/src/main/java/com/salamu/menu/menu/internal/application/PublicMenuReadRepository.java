package com.salamu.menu.menu.internal.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantId;

/** Tenant-scoped, bounded read port for the current published public menu. */
public interface PublicMenuReadRepository {

    Optional<MenuHeader> findCurrentPublished(TenantId tenantId);

    List<MenuCategoryRow> findCategories(TenantId tenantId, UUID menuVersionId);

    List<MenuItemRow> findItems(TenantId tenantId, UUID menuVersionId);

    record MenuHeader(
            UUID menuVersionId,
            UUID restaurantId,
            long version,
            Instant publishedAt,
            String currency) {
    }

    record MenuCategoryRow(
            String categoryId,
            String name,
            String description,
            TenantAssetId imageAssetId,
            int sortOrder) {
    }

    record MenuItemRow(
            String menuItemId,
            String categoryId,
            String name,
            String description,
            long priceMinor,
            String currency,
            TenantAssetId imageAssetId,
            boolean available,
            int sortOrder) {
    }
}
