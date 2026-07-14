package com.salamu.menu.tenancy.api;

import java.util.Map;
import java.util.Optional;

/**
 * Privileged non-HTTP boundary for deterministic staged asset provisioning.
 *
 * <p>The caller must pass the exact lifecycle expected at every database boundary. MIG-001 always uses
 * {@link TenantLifecycle#SUSPENDED}; accepting {@link TenantLifecycle#ACTIVE} keeps the use case reusable without
 * weakening the ordinary active-only {@link TenantAssetService}. Deleted tenants are never accepted.
 */
public interface TenantAssetProvisioningService {

    TenantAssetProvisioningResult provision(
            TenantId tenantId,
            TenantAssetImportRequest request,
            ProvisioningRunFingerprint runFingerprint,
            TenantLifecycle expectedLifecycle);

    /**
     * Inspects a registered object and verifies its storage provenance without returning a private object key.
     */
    Optional<TenantAssetProvisioningAsset> inspect(
            TenantId tenantId,
            AssetKind kind,
            String sha256,
            ProvisioningRunFingerprint runFingerprint,
            TenantLifecycle expectedLifecycle);

    /**
     * Zero-write DB-only batch inspection. It starts a read transaction when called standalone and joins an existing
     * transaction otherwise. Every expected ID must be an active asset row owned by the tenant with the exact kind.
     * The lifecycle may be active or suspended; deleted tenants are rejected. No run ownership is inferred here.
     */
    Map<TenantAssetId, TenantAssetProvisioningAsset> inspectPreparedAssetReferences(
            TenantId tenantId,
            Map<TenantAssetId, AssetKind> expectedAssets,
            TenantLifecycle expectedLifecycle);

    /**
     * DB-only validation for final writers. The call must join an existing transaction, requires a suspended tenant,
     * and verifies every expected ID is an active row owned by the tenant with the exact expected kind. Before calling,
     * the migration orchestrator must already hold and verify the locked ledger tuple
     * {@code (run, tenantId, assetId, expectedKind, expectedSha256)}. This port does not prove run ownership.
     */
    void validatePreparedAssetReferences(
            TenantId tenantId,
            Map<TenantAssetId, AssetKind> expectedAssets,
            TenantLifecycle expectedLifecycle);

    /**
     * Performs the object-storage network reconciliation before the caller starts its database cleanup transaction.
     */
    TenantAssetCleanupProof verifyCleanupProvenance(
            TenantId tenantId,
            TenantAssetProvisioningProvenance provenance);

    /**
     * Removes only an unreferenced registry row owned by a suspended tenant, using tenancy-issued proof. Object
     * deletion must happen afterwards. This method joins the caller's cleanup transaction and performs no network I/O.
     */
    TenantAssetCleanupCandidate removeRegistrationForCleanup(
            TenantId tenantId,
            TenantAssetCleanupProof proof);

    /**
     * Deletes an object only after a fresh database check proves the registry row is absent and the tenant is either
     * still suspended or already absent after the same database-first cleanup transaction. An active or deleted tenant
     * remains a hard conflict.
     */
    TenantAssetObjectCleanupDisposition deleteObjectForCleanup(
            TenantId tenantId,
            TenantAssetCleanupCandidate candidate);
}
