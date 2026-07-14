package com.salamu.menu.menu.api;

import java.util.Objects;
import java.util.regex.Pattern;

import com.salamu.menu.tenancy.api.TenantAssetId;

/** Immutable tenant-asset identity and content fingerprint embedded in menu semantics. */
public record MenuAssetReference(TenantAssetId assetId, String sha256) {

    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public MenuAssetReference {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(sha256, "sha256");
        if (!LOWERCASE_SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Menu asset sha256 must be lowercase hexadecimal");
        }
    }
}
