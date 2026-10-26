package com.salamu.menu.asset.internal.application;

import java.util.Objects;

import com.salamu.menu.asset.api.AssetKind;

public record AssetObject(
        AssetKind kind,
        AssetObjectKey key,
        String contentType,
        byte[] content,
        AssetSha256 sha256) {

    public AssetObject {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(sha256, "sha256");
        ValidatedAssetContent validated = new AssetContentPolicy().validate(
                new AssetUploadContent(kind, contentType, key.extension(), content));
        if (!validated.sha256().equals(sha256)) {
            throw new IllegalArgumentException("content does not match sha256");
        }
        if (!AssetObjectKey.canonical(kind, sha256, validated.extension()).equals(key)) {
            throw new IllegalArgumentException("Asset object key is not canonical for its content");
        }
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public long sizeBytes() {
        return content.length;
    }

    public AssetObjectDescriptor descriptor() {
        return new AssetObjectDescriptor(kind, key, contentType, sizeBytes(), sha256);
    }
}
