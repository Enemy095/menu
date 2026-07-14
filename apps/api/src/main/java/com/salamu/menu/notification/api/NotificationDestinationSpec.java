package com.salamu.menu.notification.api;

import java.util.Objects;
import java.util.regex.Pattern;

import com.salamu.menu.tenancy.api.TenantId;

/** Immutable input containing an already resolved, metadata-ready opaque reference. */
public record NotificationDestinationSpec(
        TenantId tenantId,
        String tenantSlug,
        NotificationChannel channel,
        String displayName,
        NotificationDestinationStatus status,
        OpaqueSecretReference secretReference,
        SecretReferenceReadiness readiness) {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    public NotificationDestinationSpec {
        Objects.requireNonNull(tenantId, "tenantId");
        tenantSlug = canonicalSlug(tenantSlug);
        Objects.requireNonNull(channel, "channel");
        displayName = canonicalDisplayName(displayName);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(secretReference, "secretReference");
        Objects.requireNonNull(readiness, "readiness");
        if (readiness != SecretReferenceReadiness.READY) {
            throw new IllegalArgumentException("Opaque secret reference metadata is not ready");
        }
    }

    static String canonicalSlug(String value) {
        Objects.requireNonNull(value, "tenantSlug");
        if (value.length() > 63 || !SLUG.matcher(value).matches()) {
            throw new IllegalArgumentException("tenantSlug has an invalid canonical value");
        }
        return value;
    }

    static String canonicalDisplayName(String value) {
        Objects.requireNonNull(value, "displayName");
        if (value.isBlank() || value.length() > 160 || !value.equals(value.strip())) {
            throw new IllegalArgumentException("displayName has an invalid canonical value");
        }
        return value;
    }
}
