package com.salamu.menu.migration.internal.application;

import java.util.Objects;
import java.util.UUID;

public record LegacyImportAssetCleanupCandidate(
        UUID runId,
        UUID tenantIdSnapshot,
        UUID assetIdSnapshot,
        String assetKind,
        LegacyImportSha256 contentSha256,
        String canonicalObjectKey,
        boolean reused) {

    public LegacyImportAssetCleanupCandidate {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(tenantIdSnapshot, "tenantIdSnapshot");
        Objects.requireNonNull(assetKind, "assetKind");
        Objects.requireNonNull(contentSha256, "contentSha256");
        Objects.requireNonNull(canonicalObjectKey, "canonicalObjectKey");
    }

    @Override
    public String toString() {
        return ("LegacyImportAssetCleanupCandidate[runId=%s, tenantIdSnapshot=%s, assetIdSnapshot=%s, assetKind=%s, "
                        + "contentSha256=%s, canonicalObjectKey=<redacted>, reused=%s]")
                .formatted(runId, tenantIdSnapshot, assetIdSnapshot, assetKind, contentSha256, reused);
    }
}
