package com.salamu.menu.asset.api;

import java.util.Objects;

/** Redacted immutable metadata returned after validation or object-store verification. */
public record AssetImportReceipt(
        AssetKind kind,
        String objectKey,
        String contentType,
        long sizeBytes,
        String sha256) {

    public AssetImportReceipt {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(sha256, "sha256");
        if (objectKey.isBlank() || contentType.isBlank() || sizeBytes <= 0) {
            throw new IllegalArgumentException("Asset import receipt is incomplete");
        }
    }
}
