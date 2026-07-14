package com.salamu.menu.asset.internal.application;

import java.util.Objects;

import com.salamu.menu.asset.api.AssetKind;

public record AssetUploadContent(AssetKind kind, String contentType, String extension, byte[] content) {

    public AssetUploadContent {
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
