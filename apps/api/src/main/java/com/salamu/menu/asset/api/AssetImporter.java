package com.salamu.menu.asset.api;

/**
 * Non-HTTP provisioning boundary. Validation is side-effect free; store is immutable
 * and idempotent according to the asset module's content-addressed policy.
 */
public interface AssetImporter {

    AssetImportReceipt validate(AssetImportRequest request);

    AssetImportReceipt validateAndStore(AssetImportRequest request);
}
