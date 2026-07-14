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
        String extension = switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/x-icon", "image/vnd.microsoft.icon" -> "ico";
            default -> throw new IllegalArgumentException("Unsupported persisted asset content type");
        };
        String expectedObjectKey = "tenants/%s/%s/%s.%s".formatted(
                tenantId.value(), kind.storageName(), sha256.value(), extension);
        if (!expectedObjectKey.equals(objectKey.value())) {
            throw new IllegalArgumentException("Persisted asset key is inconsistent with its tenant metadata");
        }
    }
}
