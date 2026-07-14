package com.salamu.menu.asset.internal.application;

import java.util.Objects;

import com.salamu.menu.asset.api.AssetKind;

public record AssetObjectDescriptor(
        AssetKind kind,
        AssetObjectKey key,
        String contentType,
        long sizeBytes,
        AssetSha256 sha256) {

    public AssetObjectDescriptor {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(sha256, "sha256");
        if (contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        if (sizeBytes > AssetContentPolicy.maximumSizeBytes(kind)) {
            throw new IllegalArgumentException("sizeBytes exceeds the maximum for its kind");
        }
        if (!AssetContentPolicy.allowsContentType(kind, contentType)) {
            throw new IllegalArgumentException("contentType is not allowed for its kind");
        }
        SupportedAssetMediaType mediaType = SupportedAssetMediaType.require(contentType);
        if (!AssetObjectKey.canonical(kind, sha256, mediaType.canonicalExtension()).equals(key)) {
            throw new IllegalArgumentException("Asset object key is not canonical for its descriptor");
        }
    }
}
