package com.salamu.menu.tenancy.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Internal, non-HTTP evidence needed by the migration ledger and database-first cleanup.
 *
 * <p>{@code canonicalObjectKey} is a private storage identifier. It must never be serialized by a public/admin
 * controller, returned by a public inspection operation, or written to logs. The temporary migration module stores it
 * only in its private asset ledger.
 */
public record TenantAssetProvisioningProvenance(
        TenantId tenantId,
        AssetKind kind,
        String contentType,
        long sizeBytes,
        String sha256,
        String canonicalObjectKey,
        ProvisioningRunFingerprint runFingerprint) {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    public TenantAssetProvisioningProvenance {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(canonicalObjectKey, "canonicalObjectKey");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        if (contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be lowercase hexadecimal");
        }
        String expectedKey = "tenants/%s/%s/%s.%s".formatted(
                tenantId.value(), kind.storageName(), sha256, extensionFor(contentType));
        if (!expectedKey.equals(canonicalObjectKey)) {
            throw new IllegalArgumentException("canonicalObjectKey does not match tenant asset metadata");
        }
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/x-icon", "image/vnd.microsoft.icon" -> "ico";
            default -> throw new IllegalArgumentException("Unsupported asset content type");
        };
    }

    @Override
    public String toString() {
        return "TenantAssetProvisioningProvenance[tenantId=%s, kind=%s, contentType=%s, sizeBytes=%d, "
                        + "sha256=%s, canonicalObjectKey=<redacted>, runFingerprint=%s]"
                .formatted(tenantId, kind, contentType, sizeBytes, sha256, runFingerprint);
    }
}
