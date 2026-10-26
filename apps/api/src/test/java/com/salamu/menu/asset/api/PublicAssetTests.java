package com.salamu.menu.asset.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class PublicAssetTests {

    @Test
    void exposesOnlySafePublicMetadata() {
        PublicAsset asset = new PublicAsset(
                new AssetId(UUID.randomUUID()),
                AssetKind.LOGO,
                URI.create("https://cdn.example.test/assets/logo/" + "a".repeat(64) + ".png"),
                "a".repeat(64));

        assertThat(asset.getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("id", "kind", "publicUrl", "sha256");
        assertThat(Arrays.stream(asset.getClass().getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet()))
                .isEqualTo(Set.of("id", "kind", "publicUrl", "sha256"));
    }

    @Test
    void rejectsUnsafeOrNonCanonicalPublicMetadata() {
        AssetId id = new AssetId(UUID.randomUUID());

        assertThatThrownBy(() -> new PublicAsset(
                        id, AssetKind.LOGO, URI.create("https://user:secret@cdn.example.test/image.png"), "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublicAsset(
                        id, AssetKind.LOGO, URI.create("https://cdn.example.test/image.png?secret=value"), "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublicAsset(
                        id, AssetKind.LOGO, URI.create("https://cdn.example.test/image.png"), "A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
