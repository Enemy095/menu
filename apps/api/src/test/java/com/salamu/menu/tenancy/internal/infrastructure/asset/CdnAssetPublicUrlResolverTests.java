package com.salamu.menu.tenancy.internal.infrastructure.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.salamu.menu.tenancy.internal.application.asset.AssetObjectKey;

class CdnAssetPublicUrlResolverTests {

    private static final AssetObjectKey OBJECT_KEY = new AssetObjectKey(
            "tenants/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/logo/"
                    + "0".repeat(64) + ".png");

    @Test
    void returnsDeterministicCredentialFreeCdnUrl() {
        TenantAssetProperties properties = properties(URI.create("https://cdn.example.test/assets///"));
        CdnAssetPublicUrlResolver resolver = new CdnAssetPublicUrlResolver(properties);

        URI first = resolver.resolve(OBJECT_KEY);
        URI second = resolver.resolve(OBJECT_KEY);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasToString("https://cdn.example.test/assets/" + OBJECT_KEY.value());
        assertThat(first.getUserInfo()).isNull();
        assertThat(first.getQuery()).isNull();
        assertThat(first.getFragment()).isNull();
    }

    @Test
    void rejectsCredentialBearingOrNonHttpCdnBaseUrl() {
        assertThatThrownBy(() -> properties(URI.create("https://user:secret@cdn.example.test/assets")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(URI.create("s3://tenant-assets")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TenantAssetProperties properties(URI cdnBaseUrl) {
        return new TenantAssetProperties(
                new TenantAssetProperties.Storage(
                        URI.create("http://localhost:9000"),
                        "us-east-1",
                        "tenant-assets-test",
                        "access",
                        "secret",
                        true),
                cdnBaseUrl);
    }
}
