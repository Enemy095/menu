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
        TenantAssetProperties properties = properties(URI.create("https://cdn.example.test/content///"));
        CdnAssetPublicUrlResolver resolver = new CdnAssetPublicUrlResolver(properties);

        URI first = resolver.resolve(OBJECT_KEY);
        URI second = resolver.resolve(OBJECT_KEY);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasToString(
                "https://cdn.example.test/content/assets/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/logo/"
                        + "0".repeat(64) + ".png");
        assertThat(first.getPath()).doesNotContain("/tenants/");
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

    @Test
    void rejectsObjectKeysOutsideTheCanonicalPublicMapping() {
        CdnAssetPublicUrlResolver resolver =
                new CdnAssetPublicUrlResolver(properties(URI.create("https://cdn.example.test")));
        AssetObjectKey unknownKind = new AssetObjectKey(
                "tenants/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/branding/"
                        + "0".repeat(64) + ".png");
        AssetObjectKey unsupportedExtension = new AssetObjectKey(
                "tenants/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/logo/"
                        + "0".repeat(64) + ".gif");

        assertThatThrownBy(() -> resolver.resolve(unknownKind)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(unsupportedExtension)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsAllWhiteLabelBrandingKinds() {
        CdnAssetPublicUrlResolver resolver =
                new CdnAssetPublicUrlResolver(properties(URI.create("https://cdn.example.test")));

        assertThat(resolveKind(resolver, "favicon"))
                .contains("/assets/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/favicon/");
        assertThat(resolveKind(resolver, "hero"))
                .contains("/assets/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/hero/");
        assertThat(resolveKind(resolver, "open_graph"))
                .contains("/assets/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/open_graph/");
    }

    private static String resolveKind(CdnAssetPublicUrlResolver resolver, String kind) {
        return resolver.resolve(new AssetObjectKey(
                "tenants/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/" + kind + "/"
                        + "0".repeat(64) + ".png"))
                .toString();
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
