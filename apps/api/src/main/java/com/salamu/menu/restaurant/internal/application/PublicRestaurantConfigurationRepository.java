package com.salamu.menu.restaurant.internal.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantId;

/** Tenant-scoped read port. Implementations must never query restaurant configuration without tenant_id. */
public interface PublicRestaurantConfigurationRepository {

    Optional<StoredConfiguration> findActiveDefault(TenantId tenantId);

    List<StoredFeature> findFeatures(TenantId tenantId);

    record StoredConfiguration(
            UUID restaurantId,
            String slug,
            String locale,
            String currency,
            String timezone,
            String displayName,
            TenantAssetId logoAssetId,
            TenantAssetId faviconAssetId,
            TenantAssetId heroAssetId,
            TenantAssetId openGraphAssetId,
            PublicRestaurantConfiguration.Hero hero,
            PublicRestaurantConfiguration.DesignTokens designTokens,
            List<PublicRestaurantConfiguration.LocalizedText> localizedTexts,
            PublicRestaurantConfiguration.SeoMetadata seoWithoutImage,
            PublicRestaurantConfiguration.PublicProfile profile,
            OffsetDateTime tenantUpdatedAt,
            OffsetDateTime restaurantUpdatedAt,
            OffsetDateTime brandingUpdatedAt,
            OffsetDateTime profileUpdatedAt) {
    }

    /**
     * Shared MVP storage contract used by CFG/MIG/ORD: the 13 known feature keys live in tenant_features.
     * preorder/delivery/pickup config contains label/default; orders config contains the limits object.
     */
    record StoredFeature(
            String key,
            boolean enabled,
            String label,
            boolean defaultOrderType,
            PublicRestaurantConfiguration.Limits limits,
            OffsetDateTime updatedAt) {
    }
}
