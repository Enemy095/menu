package com.salamu.menu.tenancy.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Temporary, non-HTTP application boundary used by the deterministic legacy importer.
 *
 * <p>Run ownership is deliberately not stored in production tenant rows. Durable ownership comes only from the
 * migration-owned ledger described by ADR-0002. Callers must hold and validate the matching ledger provenance
 * before invoking mutating operations; semantic equality alone never grants ownership of an existing suspended
 * tenant.</p>
 */
public interface TenantProvisioningService {

    /**
     * Must join the advisory-locked acquisition transaction owned by the migration orchestrator. Before this call,
     * the orchestrator must inspect the locked ledger and reject a suspended skeleton without matching run
     * provenance. A returned semantic-equal skeleton is reusable only because that ledger proof already exists; it
     * must never be adopted based on tenant-table equality.
     */
    TenantProvisioningState provisionSuspendedSkeleton(
            SuspendedTenantSpec spec,
            ProvisioningRunFingerprint runFingerprint);

    /** Zero-write inspection. Unknown slugs return empty; deleted rows fail closed. */
    Optional<TenantProvisioningState> inspectProvisioningState(String slug);

    /**
     * Exact tenant-scoped zero-write inspection for module-owned provisioning ports. This method does not
     * resolve or parse a host, path, slug, or client-supplied tenant hint.
     */
    Optional<TenantProvisioningState> inspectProvisioningState(TenantId tenantId);

    /** Must join an already active orchestration transaction. */
    TenantSemanticHash writeTenantConfiguration(
            TenantConfigurationSnapshot snapshot,
            ProvisioningRunFingerprint runFingerprint);

    /** Must join the final orchestration transaction and observe its uncommitted writes. */
    TenantSemanticHash verifyPreparedConfiguration(
            TenantConfigurationSnapshot expected,
            ProvisioningRunFingerprint runFingerprint);

    /** Must be the last business action in the final orchestration transaction. */
    void activateCompletedBatch(
            List<TenantActivationCandidate> candidates,
            ProvisioningRunFingerprint runFingerprint);

    /** Must run before tenant-asset registry cleanup in the same ledger-locked cleanup transaction. */
    void cleanupTenantConfiguration(
            UUID runId,
            ProvisioningRunFingerprint runFingerprint,
            List<TenantCleanupCandidate> candidates);

    /**
     * Must run after tenant-asset registry cleanup in the ledger-locked cleanup transaction. The run identity is validated by the migration
     * orchestrator; this module independently verifies exact tenant identity, suspended lifecycle,
     * absent configuration, and absence of tenancy-owned domain/asset references.
     */
    void cleanupSuspendedRun(
            UUID runId,
            ProvisioningRunFingerprint runFingerprint,
            List<TenantCleanupCandidate> candidates);
}
