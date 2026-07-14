package com.salamu.menu.migration.internal.application;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record LegacyImportAssetPlan(
        String tenantSlug,
        UUID tenantId,
        String assetKind,
        LegacyImportSha256 sourceSha256,
        LegacyImportSha256 contentSha256,
        long sizeBytes,
        String canonicalObjectKey) {

    private static final Pattern TENANT_SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern ASSET_KIND = Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+)*");

    public LegacyImportAssetPlan {
        Objects.requireNonNull(tenantSlug, "tenantSlug");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(assetKind, "assetKind");
        Objects.requireNonNull(sourceSha256, "sourceSha256");
        Objects.requireNonNull(contentSha256, "contentSha256");
        Objects.requireNonNull(canonicalObjectKey, "canonicalObjectKey");
        if (tenantSlug.length() > 63 || !TENANT_SLUG.matcher(tenantSlug).matches()) {
            throw new IllegalArgumentException("tenantSlug has an invalid format");
        }
        if (assetKind.length() > 64 || !ASSET_KIND.matcher(assetKind).matches()) {
            throw new IllegalArgumentException("assetKind has an invalid format");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes cannot be negative");
        }
        if (canonicalObjectKey.isBlank()
                || canonicalObjectKey.length() > 1024
                || canonicalObjectKey.contains("../")
                || canonicalObjectKey.contains("/..")) {
            throw new IllegalArgumentException("canonicalObjectKey has an invalid format");
        }
    }

    @Override
    public String toString() {
        return ("LegacyImportAssetPlan[tenantSlug=%s, tenantId=%s, assetKind=%s, sourceSha256=%s, "
                        + "contentSha256=%s, sizeBytes=%d, canonicalObjectKey=<redacted>]")
                .formatted(tenantSlug, tenantId, assetKind, sourceSha256, contentSha256, sizeBytes);
    }
}
