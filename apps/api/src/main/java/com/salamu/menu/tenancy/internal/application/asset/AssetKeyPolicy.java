package com.salamu.menu.tenancy.internal.application.asset;

import org.springframework.stereotype.Component;

import com.salamu.menu.tenancy.api.TenantId;

@Component
final class AssetKeyPolicy {

    AssetObjectKey create(TenantId tenantId, ValidatedAssetContent asset) {
        return new AssetObjectKey("tenants/%s/%s/%s.%s".formatted(
                tenantId.value(),
                asset.kind().storageName(),
                asset.sha256().value(),
                asset.extension()));
    }
}
