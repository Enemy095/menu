package com.salamu.menu.asset.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.salamu.menu.asset.api.AssetKind;
import com.salamu.menu.asset.internal.application.AssetObjectKey;
import com.salamu.menu.asset.internal.application.AssetSha256;

class CdnAssetPublicUrlResolverTests {

    private static final AssetObjectKey KEY = AssetObjectKey.canonical(
            AssetKind.MENU_ITEM,
            new AssetSha256("a".repeat(64)),
            "webp");

    @Test
    void resolvesCanonicalKeyAgainstConfiguredBaseUrl() {
        CdnAssetPublicUrlResolver resolver = new CdnAssetPublicUrlResolver(
                URI.create("https://cdn.example.test/public/"));

        assertThat(resolver.resolve(KEY)).isEqualTo(URI.create(
                "https://cdn.example.test/public/assets/menu_item_image/" + "a".repeat(64) + ".webp"));
    }

    @Test
    void rejectsBaseUrlsThatCouldLeakCredentialsOrAlterResolution() {
        assertThatThrownBy(() -> new CdnAssetPublicUrlResolver(URI.create("//cdn.example.test")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CdnAssetPublicUrlResolver(
                        URI.create("https://user:secret@cdn.example.test")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CdnAssetPublicUrlResolver(
                        URI.create("https://cdn.example.test?token=secret")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CdnAssetPublicUrlResolver(
                        URI.create("https://cdn.example.test/base/../other")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
