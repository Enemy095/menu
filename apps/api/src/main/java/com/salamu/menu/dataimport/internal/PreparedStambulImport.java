package com.salamu.menu.dataimport.internal;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import com.salamu.menu.asset.api.AssetImportReceipt;
import com.salamu.menu.asset.api.AssetImportRequest;

record PreparedStambulImport(
        StambulApprovedBundle bundle,
        AssetImportRequest assetRequest,
        AssetImportReceipt assetReceipt,
        String bundleSha256,
        String effectiveSha256,
        String timezone,
        URI canonicalUrl,
        UUID namespace,
        UUID assetId,
        UUID restaurantId,
        UUID menuVersionId,
        Map<String, UUID> categoryIds,
        Map<String, UUID> itemIds) {
}
