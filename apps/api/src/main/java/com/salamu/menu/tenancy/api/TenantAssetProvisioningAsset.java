package com.salamu.menu.tenancy.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Storage-neutral asset metadata returned by the privileged provisioning boundary.
 *
 * <p>Unlike {@link TenantAsset}, this type intentionally has no CDN URL. Suspended tenant provisioning and
 * inspection must not resolve or expose a public asset location.
 */
public record TenantAssetProvisioningAsset(
        TenantAssetId id,
        TenantId tenantId,
        AssetKind kind,
        String contentType,
        long sizeBytes,
        String sha256) {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    public TenantAssetProvisioningAsset {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(sha256, "sha256");
        if (contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be lowercase hexadecimal");
        }
    }
}
