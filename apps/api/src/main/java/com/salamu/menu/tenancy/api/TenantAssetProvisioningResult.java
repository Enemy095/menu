package com.salamu.menu.tenancy.api;

import java.util.Objects;

/** Typed result consumed by the temporary migration ledger; not an HTTP response model. */
public record TenantAssetProvisioningResult(
        TenantAssetProvisioningAsset asset,
        TenantAssetProvisioningDisposition disposition,
        TenantAssetProvisioningProvenance provenance) {

    public TenantAssetProvisioningResult {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(provenance, "provenance");
        if (!asset.tenantId().equals(provenance.tenantId())
                || asset.kind() != provenance.kind()
                || !asset.contentType().equals(provenance.contentType())
                || asset.sizeBytes() != provenance.sizeBytes()
                || !asset.sha256().equals(provenance.sha256())) {
            throw new IllegalArgumentException("Asset result and provenance do not describe the same content");
        }
    }
}
