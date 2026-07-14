package com.salamu.menu.restaurant.api;

import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;
import com.salamu.menu.tenancy.api.TenantId;

/** Temporary non-HTTP boundary for deterministic default-restaurant import. */
public interface RestaurantProvisioningService {

    /** Zero-write inspection for an active or suspended tenant. */
    Optional<DefaultRestaurantSnapshot> inspectDefaultRestaurant(TenantId tenantId);

    /** Joins the migration final transaction and creates or compares the natural-key row. */
    DefaultRestaurantSnapshot writeDefaultRestaurant(
            DefaultRestaurantSpec spec,
            ProvisioningRunFingerprint runFingerprint);

    /** Observes prepared, including uncommitted, state in the migration final transaction. */
    RestaurantSemanticHash verifyPreparedDefaultRestaurant(
            DefaultRestaurantSpec expected,
            ProvisioningRunFingerprint runFingerprint);

    /** Removes only the exact row proven by the ledger while its tenant remains suspended. */
    void cleanupDefaultRestaurant(
            UUID runId,
            ProvisioningRunFingerprint runFingerprint,
            DefaultRestaurantCleanupCandidate candidate);
}
