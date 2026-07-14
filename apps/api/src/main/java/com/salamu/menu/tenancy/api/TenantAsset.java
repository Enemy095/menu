package com.salamu.menu.tenancy.api;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable tenant-scoped asset reference returned to backend importers and readers. */
public record TenantAsset(
        TenantAssetId id,
        TenantId tenantId,
        AssetKind kind,
        String contentType,
        long sizeBytes,
        String sha256,
        URI publicUrl) {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    public TenantAsset {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(publicUrl, "publicUrl");
        if (contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be lowercase hexadecimal");
        }
        boolean http = "http".equalsIgnoreCase(publicUrl.getScheme())
                || "https".equalsIgnoreCase(publicUrl.getScheme());
        if (!publicUrl.isAbsolute()
                || !http
                || publicUrl.getHost() == null
                || publicUrl.getUserInfo() != null
                || publicUrl.getQuery() != null
                || publicUrl.getFragment() != null) {
            throw new IllegalArgumentException(
                    "publicUrl must be an absolute HTTP(S) URL without credentials, query or fragment");
        }
    }
}
