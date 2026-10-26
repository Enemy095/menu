package com.salamu.menu.asset.internal.infrastructure;

import java.net.URI;
import java.util.Objects;

import com.salamu.menu.asset.internal.application.AssetObjectKey;
import com.salamu.menu.asset.internal.application.AssetPublicUrlResolver;

public final class CdnAssetPublicUrlResolver implements AssetPublicUrlResolver {

    private final String baseUrl;

    public CdnAssetPublicUrlResolver(AssetProperties properties) {
        this(Objects.requireNonNull(properties, "properties").cdnBaseUrl());
    }

    public CdnAssetPublicUrlResolver(URI cdnBaseUrl) {
        validateBaseUrl(cdnBaseUrl);
        this.baseUrl = stripTrailingSlash(cdnBaseUrl.toASCIIString());
    }

    @Override
    public URI resolve(AssetObjectKey objectKey) {
        Objects.requireNonNull(objectKey, "objectKey");
        return URI.create(baseUrl + "/" + objectKey.value());
    }

    static void validateBaseUrl(URI baseUrl) {
        Objects.requireNonNull(baseUrl, "cdnBaseUrl");
        String scheme = baseUrl.getScheme();
        if (!baseUrl.isAbsolute()
                || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null
                || !baseUrl.normalize().equals(baseUrl)) {
            throw new IllegalArgumentException("app.assets.cdn-base-url must be a safe absolute HTTP(S) URL");
        }
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
