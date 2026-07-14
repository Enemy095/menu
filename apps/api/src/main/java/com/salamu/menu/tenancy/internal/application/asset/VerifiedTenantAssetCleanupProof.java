package com.salamu.menu.tenancy.internal.application.asset;

import java.util.Objects;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.TenantAssetCleanupProof;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningProvenance;
import com.salamu.menu.tenancy.api.TenantId;

final class VerifiedTenantAssetCleanupProof implements TenantAssetCleanupProof {

    private final TenantAssetProvisioningProvenance provenance;
    private final boolean objectPresent;

    VerifiedTenantAssetCleanupProof(
            TenantAssetProvisioningProvenance provenance,
            boolean objectPresent) {
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        this.objectPresent = objectPresent;
    }

    TenantAssetProvisioningProvenance provenance() {
        return provenance;
    }

    @Override
    public TenantId tenantId() {
        return provenance.tenantId();
    }

    @Override
    public AssetKind kind() {
        return provenance.kind();
    }

    @Override
    public String sha256() {
        return provenance.sha256();
    }

    @Override
    public boolean objectPresent() {
        return objectPresent;
    }

    @Override
    public String toString() {
        return "VerifiedTenantAssetCleanupProof[tenantId=%s, kind=%s, sha256=%s, objectPresent=%s]"
                .formatted(tenantId(), kind(), sha256(), objectPresent);
    }
}
