package com.salamu.menu.notification.api;

import java.util.Objects;
import java.util.UUID;

import com.salamu.menu.tenancy.api.TenantId;

/** Exact redacted proof expected before an import-owned destination can be removed. */
public record NotificationDestinationCleanupCandidate(
        TenantId tenantId,
        String tenantSlug,
        UUID destinationId,
        NotificationChannel channel,
        String displayName,
        NotificationDestinationSemanticHash expectedSemanticHash) {

    public NotificationDestinationCleanupCandidate {
        Objects.requireNonNull(tenantId, "tenantId");
        tenantSlug = NotificationDestinationSpec.canonicalSlug(tenantSlug);
        Objects.requireNonNull(destinationId, "destinationId");
        Objects.requireNonNull(channel, "channel");
        displayName = NotificationDestinationSpec.canonicalDisplayName(displayName);
        Objects.requireNonNull(expectedSemanticHash, "expectedSemanticHash");
    }
}
