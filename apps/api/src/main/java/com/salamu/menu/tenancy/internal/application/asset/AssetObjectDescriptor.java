package com.salamu.menu.tenancy.internal.application.asset;

import java.util.Objects;

public record AssetObjectDescriptor(
        AssetObjectKey key,
        String contentType,
        long sizeBytes,
        AssetSha256 sha256) {

    public AssetObjectDescriptor {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(sha256, "sha256");
        if (contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
    }

    public static AssetObjectDescriptor from(AssetObject object) {
        return new AssetObjectDescriptor(object.key(), object.contentType(), object.sizeBytes(), object.sha256());
    }
}
