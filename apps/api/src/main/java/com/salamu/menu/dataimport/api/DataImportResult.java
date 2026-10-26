package com.salamu.menu.dataimport.api;

import java.util.Objects;
import java.util.UUID;

public record DataImportResult(
        String outcome,
        String selection,
        String bundleSha256,
        String effectiveSha256,
        UUID assetId,
        UUID restaurantId,
        UUID menuVersionId,
        int categoryCount,
        int itemCount,
        String assetSha256,
        String configurationRepresentationHash,
        String menuRepresentationHash) {

    public DataImportResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(bundleSha256, "bundleSha256");
        Objects.requireNonNull(effectiveSha256, "effectiveSha256");
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(restaurantId, "restaurantId");
        Objects.requireNonNull(menuVersionId, "menuVersionId");
        Objects.requireNonNull(assetSha256, "assetSha256");
    }
}
