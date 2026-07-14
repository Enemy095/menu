package com.salamu.menu.menu.internal.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.menu.api.PublishedMenuSnapshot;
import com.salamu.menu.menu.internal.application.DeterministicMenuIds.ExpectedMenuIds;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantId;

/** Module-owned persistence boundary for one complete versioned menu snapshot. */
public interface MenuProvisioningRepository {

    Optional<StoredMenu> find(TenantId tenantId, UUID restaurantId, long version);

    /** Locks an existing complete snapshot in the same canonical order used by writers and cleanup. */
    Optional<StoredMenu> findForUpdate(TenantId tenantId, UUID restaurantId, long version);

    List<Long> findPublishedVersions(TenantId tenantId, UUID restaurantId);

    void insert(PublishedMenuSnapshot snapshot, ExpectedMenuIds ids, Instant createdAt);

    int delete(TenantId tenantId, UUID restaurantId, long version, UUID menuVersionId);

    record StoredMenu(StoredHeader header, List<StoredCategory> categories, List<StoredItem> items) {

        public StoredMenu {
            categories = List.copyOf(categories);
            items = List.copyOf(items);
        }
    }

    record StoredHeader(
            UUID menuVersionId,
            TenantId tenantId,
            UUID restaurantId,
            long version,
            String status,
            Instant publishedAt) {
    }

    record StoredCategory(
            UUID internalId,
            String categoryId,
            String name,
            String description,
            TenantAssetId imageAssetId,
            int sortOrder,
            boolean available) {
    }

    record StoredItem(
            UUID internalId,
            UUID categoryInternalId,
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
