package com.salamu.menu.notification.api;

import java.util.Objects;
import java.util.UUID;

import com.salamu.menu.tenancy.api.TenantId;

/** Sanitized destination state. The source opaque reference is intentionally absent. */
public record NotificationDestinationSnapshot(
        UUID destinationId,
        TenantId tenantId,
        NotificationChannel channel,
        String displayName,
        NotificationDestinationStatus status,
        SecretReferenceFingerprint referenceFingerprint,
        SecretReferenceReadiness readiness,
        NotificationDestinationSemanticHash semanticHash) {

    public NotificationDestinationSnapshot {
        Objects.requireNonNull(destinationId, "destinationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(channel, "channel");
        displayName = NotificationDestinationSpec.canonicalDisplayName(displayName);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(referenceFingerprint, "referenceFingerprint");
        Objects.requireNonNull(readiness, "readiness");
        Objects.requireNonNull(semanticHash, "semanticHash");
        if (readiness != SecretReferenceReadiness.READY) {
            throw new IllegalArgumentException("Persisted destination reference is not metadata-ready");
        }
    }
}
