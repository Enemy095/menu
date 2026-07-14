package com.salamu.menu.tenancy.internal.application.asset;

import java.util.Objects;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.TenantAssetCleanupCandidate;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningProvenance;
import com.salamu.menu.tenancy.api.TenantId;

final class VerifiedTenantAssetCleanupCandidate implements TenantAssetCleanupCandidate {

    private final TenantAssetProvisioningProvenance provenance;
    private final boolean registrationRemoved;

    VerifiedTenantAssetCleanupCandidate(
            TenantAssetProvisioningProvenance provenance,
            boolean registrationRemoved) {
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        this.registrationRemoved = registrationRemoved;
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
    public boolean registrationRemoved() {
        return registrationRemoved;
    }

    @Override
    public String toString() {
        return "VerifiedTenantAssetCleanupCandidate[tenantId=%s, kind=%s, sha256=%s, registrationRemoved=%s]"
                .formatted(tenantId(), kind(), sha256(), registrationRemoved);
    }
}
