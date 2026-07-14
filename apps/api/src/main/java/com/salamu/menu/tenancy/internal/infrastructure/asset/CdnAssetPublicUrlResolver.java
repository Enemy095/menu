package com.salamu.menu.tenancy.internal.infrastructure.asset;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.salamu.menu.tenancy.internal.application.asset.AssetObjectKey;
import com.salamu.menu.tenancy.internal.application.asset.AssetPublicUrlResolver;

@Component
final class CdnAssetPublicUrlResolver implements AssetPublicUrlResolver {

    private static final Pattern CANONICAL_OBJECT_KEY = Pattern.compile(
            "^tenants/"
                    + "(?<tenantId>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/"
                    + "(?<kind>logo|favicon|hero|open_graph|menu_category_image|menu_item_image)/"
                    + "(?<filename>[0-9a-f]{64}\\.(?:png|jpg|webp|ico))$");

    private final String baseUrl;

    CdnAssetPublicUrlResolver(TenantAssetProperties properties) {
        this.baseUrl = stripTrailingSlashes(properties.cdnBaseUrl().toASCIIString());
    }

    @Override
    public URI resolve(AssetObjectKey objectKey) {
        Matcher canonical = CANONICAL_OBJECT_KEY.matcher(objectKey.value());
        if (!canonical.matches()) {
            throw new IllegalArgumentException("Asset object key cannot be mapped to the public CDN namespace");
        }
        return URI.create("%s/assets/%s/%s/%s".formatted(
                baseUrl,
                canonical.group("tenantId"),
                canonical.group("kind"),
                canonical.group("filename")));
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
