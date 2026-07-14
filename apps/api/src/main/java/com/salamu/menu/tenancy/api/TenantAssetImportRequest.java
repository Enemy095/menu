package com.salamu.menu.tenancy.api;

import java.util.Objects;

/** In-memory import payload. Browser or direct-to-storage uploads are intentionally not supported. */
public record TenantAssetImportRequest(
        AssetKind kind,
        String contentType,
        String extension,
        byte[] content) {

    public TenantAssetImportRequest {
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
