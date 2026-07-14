package com.salamu.menu.tenancy.internal.infrastructure.asset;

import java.net.URI;

import org.springframework.stereotype.Component;

import com.salamu.menu.tenancy.internal.application.asset.AssetObjectKey;
import com.salamu.menu.tenancy.internal.application.asset.AssetPublicUrlResolver;

@Component
final class CdnAssetPublicUrlResolver implements AssetPublicUrlResolver {

    private final String baseUrl;

    CdnAssetPublicUrlResolver(TenantAssetProperties properties) {
        this.baseUrl = stripTrailingSlashes(properties.cdnBaseUrl().toASCIIString());
    }

    @Override
    public URI resolve(AssetObjectKey objectKey) {
        return URI.create(baseUrl + "/" + objectKey.value());
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
