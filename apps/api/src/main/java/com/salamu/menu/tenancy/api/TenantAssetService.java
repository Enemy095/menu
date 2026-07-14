package com.salamu.menu.tenancy.api;

import java.util.Collection;
import java.util.Map;

/** Tenant-scoped application API for deterministic import-time assets. */
public interface TenantAssetService {

    TenantAsset importAsset(TenantId tenantId, TenantAssetImportRequest request);

    TenantAsset getAsset(TenantId tenantId, TenantAssetId assetId);

    /**
     * Resolves up to 500 active assets in one tenant-scoped batch.
     *
     * <p>An empty input returns an immutable empty map without looking up the tenant. Duplicate identifiers are
     * collapsed. Unknown, deleted, and identifiers owned by another tenant are omitted. A non-empty lookup for an
     * unknown or inactive tenant fails with {@link TenantAssetNotFoundException}. Map iteration order is unspecified.
     */
    Map<TenantAssetId, TenantAsset> getActiveAssets(
            TenantId tenantId,
            Collection<TenantAssetId> assetIds);
}
