package com.salamu.menu.tenancy.internal.application.asset;

import java.util.Objects;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantId;

public record AssetRecord(
        TenantAssetId id,
        TenantId tenantId,
        AssetKind kind,
        AssetObjectKey objectKey,
        String contentType,
        long sizeBytes,
        AssetSha256 sha256,
        AssetStatus status) {

    public AssetRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(status, "status");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
    }
}
