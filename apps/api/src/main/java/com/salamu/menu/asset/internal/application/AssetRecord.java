package com.salamu.menu.asset.internal.application;

import java.time.Instant;
import java.util.Objects;

import com.salamu.menu.asset.api.AssetId;
import com.salamu.menu.asset.api.AssetKind;
import com.salamu.menu.asset.api.AssetUnavailableException;

public record AssetRecord(
        AssetId id,
        AssetKind kind,
        AssetObjectKey objectKey,
        String contentType,
        long sizeBytes,
        AssetSha256 sha256,
        AssetStatus status,
        Instant createdAt) {

    public AssetRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (sizeBytes <= 0 || sizeBytes > AssetContentPolicy.maximumSizeBytes(kind)) {
            throw new AssetUnavailableException();
        }
        if (!AssetContentPolicy.allowsContentType(kind, contentType)) {
            throw new AssetUnavailableException();
        }
        SupportedAssetMediaType mediaType;
        try {
            mediaType = SupportedAssetMediaType.require(contentType);
        } catch (InvalidAssetContentException exception) {
            throw new AssetUnavailableException();
        }
        AssetObjectKey canonical = AssetObjectKey.canonical(kind, sha256, mediaType.canonicalExtension());
        if (!canonical.equals(objectKey)) {
            throw new AssetUnavailableException();
        }
    }

    public boolean isActive() {
        return status == AssetStatus.ACTIVE;
    }
}
