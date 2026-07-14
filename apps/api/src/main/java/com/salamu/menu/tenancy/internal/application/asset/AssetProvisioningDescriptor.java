package com.salamu.menu.tenancy.internal.application.asset;

import java.util.Objects;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.TenantId;

public record AssetProvisioningDescriptor(
        TenantId tenantId,
        AssetKind kind,
        AssetObjectDescriptor object) {

    public AssetProvisioningDescriptor {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(object, "object");
        String prefix = "tenants/%s/%s/".formatted(tenantId.value(), kind.storageName());
        if (!object.key().value().startsWith(prefix)) {
            throw new IllegalArgumentException("Asset descriptor key is outside its tenant scope");
        }
    }
}
