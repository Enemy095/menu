package com.salamu.menu.tenancy.internal.application.asset;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantId;

public interface AssetPersistence {

    void requireActiveTenant(TenantId tenantId);

    AssetRecord registerPending(
            TenantId tenantId,
            AssetKind kind,
            AssetObjectKey objectKey,
            String contentType,
            long sizeBytes,
            AssetSha256 sha256);

    Optional<AssetRecord> findByContent(TenantId tenantId, AssetKind kind, AssetSha256 sha256);

    Optional<AssetRecord> findActiveById(TenantId tenantId, TenantAssetId assetId);

    List<AssetRecord> findAllActiveByIds(TenantId tenantId, Set<TenantAssetId> assetIds);

    AssetRecord activate(TenantId tenantId, TenantAssetId assetId);
}
