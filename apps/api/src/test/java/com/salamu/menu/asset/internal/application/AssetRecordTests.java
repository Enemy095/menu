package com.salamu.menu.asset.internal.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.salamu.menu.asset.api.AssetId;
import com.salamu.menu.asset.api.AssetKind;
import com.salamu.menu.asset.api.AssetUnavailableException;

class AssetRecordTests {

    @Test
    void rejectsKindHashOrExtensionThatDoesNotMatchCanonicalKey() {
        AssetSha256 sha = new AssetSha256("a".repeat(64));
        AssetObjectKey logoKey = AssetObjectKey.canonical(AssetKind.LOGO, sha, "png");

        assertThatThrownBy(() -> new AssetRecord(
                        new AssetId(UUID.randomUUID()),
                        AssetKind.HERO,
                        logoKey,
                        "image/png",
                        8,
                        sha,
                        AssetStatus.ACTIVE,
                        Instant.parse("2026-01-01T00:00:00Z")))
                .isInstanceOf(AssetUnavailableException.class)
                .hasMessage("Required asset is unavailable");
        assertThatThrownBy(() -> new AssetRecord(
                        new AssetId(UUID.randomUUID()),
                        AssetKind.LOGO,
                        logoKey,
                        "image/jpeg",
                        8,
                        sha,
                        AssetStatus.ACTIVE,
                        Instant.parse("2026-01-01T00:00:00Z")))
                .isInstanceOf(AssetUnavailableException.class)
                .hasMessage("Required asset is unavailable");
        assertThatThrownBy(() -> new AssetRecord(
                        new AssetId(UUID.randomUUID()),
                        AssetKind.LOGO,
                        logoKey,
                        "image/png",
                        8,
                        new AssetSha256("b".repeat(64)),
                        AssetStatus.ACTIVE,
                        Instant.parse("2026-01-01T00:00:00Z")))
                .isInstanceOf(AssetUnavailableException.class)
                .hasMessage("Required asset is unavailable");
    }
}
