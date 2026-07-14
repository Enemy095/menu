package com.salamu.menu.tenancy.api;

/**
 * Opaque database-first cleanup handoff issued by tenancy.
 *
 * <p>Implementations are internal. Object deletion rejects caller-created implementations, preventing forged
 * canonical keys or run provenance from reaching storage cleanup.
 */
public interface TenantAssetCleanupCandidate {

    TenantId tenantId();

    AssetKind kind();

    String sha256();

    boolean registrationRemoved();
}
