package com.salamu.menu.asset.api;

import java.util.Objects;

/** Trusted non-HTTP input for an approved content publication tool. */
public record AssetImportRequest(
        AssetKind kind,
        String contentType,
        String extension,
        byte[] content) {

    public AssetImportRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(extension, "extension");
        Objects.requireNonNull(content, "content");
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
