package com.salamu.menu.menu.api;

import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;
import com.salamu.menu.tenancy.api.TenantId;

/**
 * Temporary non-HTTP boundary for deterministic legacy menu provisioning. Before any write or cleanup, the migration
 * orchestrator must verify exact run provenance under its locked ledger row. A run fingerprint is only the namespace
 * for deterministic menu IDs; asset ownership/content is verified independently by the tenancy module.
 */
public interface MenuProvisioningService {

    /** Zero-write exact tenant/restaurant/version inspection for active or suspended tenants. */
    Optional<MenuProvisioningInspection> inspectMenuSnapshot(
            TenantId tenantId,
            UUID restaurantId,
            long version);

    /** Joins the final orchestration transaction and creates or semantically compares a complete snapshot. */
    MenuProvisioningResult writeMenuSnapshot(
            PublishedMenuSnapshot snapshot,
            ProvisioningRunFingerprint runFingerprint);

    /** Joins the final transaction and verifies the prepared, possibly uncommitted, snapshot. */
    MenuProvisioningInspection verifyPreparedMenu(
            PublishedMenuSnapshot expected,
            ProvisioningRunFingerprint runFingerprint);

    /** Deletes only an exact run-owned snapshot while its tenant is still suspended. */
    void cleanupMenuSnapshot(
            MenuCleanupCandidate candidate,
            ProvisioningRunFingerprint runFingerprint);
}
