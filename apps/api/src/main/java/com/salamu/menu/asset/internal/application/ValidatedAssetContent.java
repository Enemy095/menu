package com.salamu.menu.asset.internal.application;

import java.util.Objects;

import com.salamu.menu.asset.api.AssetKind;

public record ValidatedAssetContent(
        AssetKind kind,
        String contentType,
        String extension,
        byte[] content,
        AssetSha256 sha256) {

    public ValidatedAssetContent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(extension, "extension");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(sha256, "sha256");
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public AssetObject toObject() {
        return new AssetObject(
                kind,
                AssetObjectKey.canonical(kind, sha256, extension),
                contentType,
                content,
                sha256);
    }
}
