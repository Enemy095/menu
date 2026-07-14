package com.salamu.menu.tenancy.internal.application.asset;

import java.util.Objects;

public record AssetObject(
        AssetObjectKey key,
        String contentType,
        byte[] content,
        AssetSha256 sha256) {

    public AssetObject {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(sha256, "sha256");
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public long sizeBytes() {
        return content.length;
    }
}
