package com.salamu.menu.migration.internal.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LegacyImportAssetProvenance(
        UUID runId,
        LegacyImportRunFingerprint runFingerprint,
        String tenantSlug,
        UUID tenantIdSnapshot,
        UUID liveTenantId,
        UUID assetIdSnapshot,
        UUID liveAssetId,
        String assetKind,
        LegacyImportSha256 sourceSha256,
        LegacyImportSha256 contentSha256,
        long sizeBytes,
        LegacyImportAssetState state,
        boolean reused,
        Instant createdAt,
        Instant updatedAt) {

    public LegacyImportAssetProvenance {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        Objects.requireNonNull(tenantSlug, "tenantSlug");
        Objects.requireNonNull(tenantIdSnapshot, "tenantIdSnapshot");
        Objects.requireNonNull(assetKind, "assetKind");
        Objects.requireNonNull(sourceSha256, "sourceSha256");
        Objects.requireNonNull(contentSha256, "contentSha256");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (reused != (state.status() == LegacyImportAssetStatus.REUSED)
                && state.status() != LegacyImportAssetStatus.CLEANED) {
            throw new IllegalArgumentException("Asset reused marker does not match its status");
        }
    }
}
