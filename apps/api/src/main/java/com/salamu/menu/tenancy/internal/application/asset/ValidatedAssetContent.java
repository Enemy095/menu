package com.salamu.menu.tenancy.internal.application.asset;

import java.util.Objects;

import com.salamu.menu.tenancy.api.AssetKind;

record ValidatedAssetContent(
        AssetKind kind,
        String contentType,
        String extension,
        byte[] content,
        AssetSha256 sha256) {

    ValidatedAssetContent {
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

    long sizeBytes() {
        return content.length;
    }
}
