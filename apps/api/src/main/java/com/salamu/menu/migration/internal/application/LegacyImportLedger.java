package com.salamu.menu.migration.internal.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary owned by the temporary migration module.
 *
 * <p>Locking and mutation methods require an already active PostgreSQL transaction. This boundary
 * never opens an independent transaction, never performs network I/O and never accesses another
 * module's tables.
 */
public interface LegacyImportLedger {

    LegacyImportAdvisoryKey advisoryKey(LegacyImportRunFingerprint fingerprint);

    void acquireTransactionLock(LegacyImportRunFingerprint fingerprint);

    LegacyImportRun createRun(LegacyImportRunDefinition definition);

    Optional<LegacyImportRun> inspectRun(UUID runId);

    Optional<LegacyImportRun> inspectRun(LegacyImportRunFingerprint fingerprint);

    Optional<LegacyImportRun> inspectOpenRun(String importerKey);

    Optional<LegacyImportRun> lockRun(UUID runId);

    boolean compareAndSetRun(
            UUID runId,
            LegacyImportRunState expected,
            LegacyImportRunState next,
            String redactedFailureCode);

    LegacyImportTenantProvenance registerTenantPlan(
            UUID runId,
            LegacyImportRunFingerprint runFingerprint,
            LegacyImportTenantPlan plan);

    boolean attachTenantAndCompareAndSet(
            UUID runId,
            String tenantSlug,
            LegacyImportTenantState expected,
            LegacyImportTenantState next,
            UUID tenantId);

    boolean compareAndSetTenant(
            UUID runId,
            String tenantSlug,
            LegacyImportTenantState expected,
            LegacyImportTenantState next);

    List<LegacyImportTenantProvenance> inspectTenants(UUID runId);

    List<LegacyImportTenantProvenance> lockTenants(UUID runId);

    LegacyImportAssetProvenance registerAssetPlan(
            UUID runId,
            LegacyImportRunFingerprint runFingerprint,
            LegacyImportAssetPlan plan);

    boolean attachAssetAndCompareAndSet(
            UUID runId,
            String tenantSlug,
            String assetKind,
            LegacyImportSha256 contentSha256,
            LegacyImportAssetState expected,
            LegacyImportAssetState next,
            UUID assetId);

    boolean compareAndSetAsset(
            UUID runId,
            String tenantSlug,
            String assetKind,
            LegacyImportSha256 contentSha256,
            LegacyImportAssetState expected,
            LegacyImportAssetState next);

    List<LegacyImportAssetProvenance> inspectAssets(UUID runId);

    List<LegacyImportAssetCleanupCandidate> lockAssetCleanupCandidates(UUID runId);
}
