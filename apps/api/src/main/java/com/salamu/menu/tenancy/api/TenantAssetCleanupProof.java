package com.salamu.menu.tenancy.api;

/**
 * Opaque, non-HTTP proof that object-storage provenance was reconciled before database cleanup.
 *
 * <p>Implementations are tenancy-owned. The cleanup writer rejects caller-created implementations.
 */
public interface TenantAssetCleanupProof {

    TenantId tenantId();

    AssetKind kind();

    String sha256();

    boolean objectPresent();
}
