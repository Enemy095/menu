package com.salamu.menu.notification.api;

import java.util.List;
import java.util.UUID;

import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;
import com.salamu.menu.tenancy.api.TenantId;

/** Temporary non-HTTP boundary for deterministic destination import and cleanup. */
public interface NotificationProvisioningService {

    /** Zero-write inspection for active or suspended tenants; references are fingerprinted. */
    List<NotificationDestinationSnapshot> inspectDestinations(TenantId tenantId);

    /** Joins the migration final transaction and creates or compares the natural-key row. */
    NotificationDestinationSnapshot writeDestination(
            NotificationDestinationSpec spec,
            ProvisioningRunFingerprint runFingerprint);

    /** Observes prepared, including uncommitted, state in the migration final transaction. */
    NotificationDestinationSemanticHash verifyPreparedDestination(
            NotificationDestinationSpec expected,
            ProvisioningRunFingerprint runFingerprint);

    /** Removes only the exact row proven by the ledger while its tenant remains suspended. */
    void cleanupDestination(
            UUID runId,
            ProvisioningRunFingerprint runFingerprint,
            NotificationDestinationCleanupCandidate candidate);
}
