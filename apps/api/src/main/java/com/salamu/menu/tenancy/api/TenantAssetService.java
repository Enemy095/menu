package com.salamu.menu.tenancy.api;

/** Tenant-scoped application API for deterministic import-time assets. */
public interface TenantAssetService {

    TenantAsset importAsset(TenantId tenantId, TenantAssetImportRequest request);

    TenantAsset getAsset(TenantId tenantId, TenantAssetId assetId);
}
