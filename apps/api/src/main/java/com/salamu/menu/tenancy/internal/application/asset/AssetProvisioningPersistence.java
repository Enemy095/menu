package com.salamu.menu.tenancy.internal.application.asset;

import java.util.Map;
import java.util.Optional;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.api.TenantLifecycle;

/** Database-only port. Every method rechecks tenant lifecycle and tenant/content ownership. */
public interface AssetProvisioningPersistence {

    Optional<AssetRecord> inspect(
            TenantId tenantId,
            AssetKind kind,
            AssetSha256 sha256,
            TenantLifecycle expectedLifecycle);

    AssetRecord registerUploaded(
            TenantId tenantId,
            AssetKind kind,
            AssetObjectKey objectKey,
            String contentType,
            long sizeBytes,
            AssetSha256 sha256,
            TenantLifecycle expectedLifecycle);

    /** Must join a caller-owned read transaction and perform zero writes or object-storage access. */
    Map<TenantAssetId, AssetRecord> inspectPreparedAssetReferences(
            TenantId tenantId,
            Map<TenantAssetId, AssetKind> expectedAssets,
            TenantLifecycle expectedLifecycle);

    /** Must join the final writer transaction and perform no object-storage access. */
    void validatePreparedAssetReferences(
            TenantId tenantId,
            Map<TenantAssetId, AssetKind> expectedAssets,
            TenantLifecycle expectedLifecycle);

    /** Must join the migration-owned cleanup transaction. */
    boolean removeUnreferencedForCleanup(
            AssetProvisioningDescriptor expected,
            TenantLifecycle expectedLifecycle);

    /**
     * Post-commit check: the tenant may already be absent after skeleton cleanup, but it may never be active/deleted.
     */
    void requireObjectDeletionAllowed(AssetProvisioningDescriptor expected);
}
