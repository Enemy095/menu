package com.salamu.menu.asset.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;

class AssetPropertiesTests {

    @Test
    void redactsCredentialsFromNestedAndRootRepresentations() {
        AssetProperties properties = properties("access-value", "secret-value");

        assertThat(properties.toString())
                .contains("<redacted>")
                .doesNotContain("access-value", "secret-value");
        assertThat(properties.storage().toString())
                .contains("<redacted>")
                .doesNotContain("access-value", "secret-value");
    }

    @Test
    void rejectsUnsafeStorageAndCdnUrlsAtConstruction() {
        assertThatThrownBy(() -> new AssetProperties.Storage(
                        URI.create("https://user:secret@storage.example.test"),
                        "us-east-1",
                        "asset-bucket",
                        "access",
                        "secret",
                        false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetProperties(
                        properties("access", "secret").storage(),
                        URI.create("https://cdn.example.test/assets/../private")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static AssetProperties properties(String accessKey, String secretKey) {
        return new AssetProperties(
                new AssetProperties.Storage(
                        URI.create("http://127.0.0.1:9000"),
                        "us-east-1",
                        "asset-bucket",
                        accessKey,
                        secretKey,
                        true),
                URI.create("https://cdn.example.test"));
    }
}
