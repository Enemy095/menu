package com.salamu.menu.dataimport.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import com.salamu.menu.asset.api.AssetImportReceipt;
import com.salamu.menu.asset.api.AssetImportRequest;
import com.salamu.menu.asset.api.AssetImporter;
import com.salamu.menu.asset.api.AssetKind;
import com.salamu.menu.dataimport.api.DataImportRequest;
import com.salamu.menu.dataimport.api.DataImportTarget;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class StambulBundleLoaderTests {

    private static final Path ROOT = repositoryRoot();
    private static final Path BUNDLE = ROOT.resolve("docs/migration/data/stambul-approved-v1.json");
    private static final String LOGO_KEY = "assets/logo/"
            + "90374c21b9cd4c15ef5b861521d0e5bcdc4571c3fae6f4aebd5c3927bfb6ee9b.png";

    @Test
    void loadsOnlyThePinnedBundleAndDerivesStableUuidV5Identifiers() {
        AssetImporter assets = mock(AssetImporter.class);
        when(assets.validate(org.mockito.ArgumentMatchers.any())).thenReturn(new AssetImportReceipt(
                AssetKind.LOGO,
                LOGO_KEY,
                "image/png",
                43728,
                "90374c21b9cd4c15ef5b861521d0e5bcdc4571c3fae6f4aebd5c3927bfb6ee9b"));

        PreparedStambulImport prepared = new StambulBundleLoader(new ObjectMapper(), assets)
                .load(DataImportRequest.localTest(BUNDLE, ROOT));

        assertThat(prepared.bundleSha256()).isEqualTo(StambulBundleLoader.APPROVED_BUNDLE_SHA256);
        assertThat(prepared.namespace()).isEqualTo(StambulBundleLoader.APPROVED_UUID_NAMESPACE);
        assertThat(prepared.assetId()).isEqualTo(UUID.fromString("41eec9b7-b68e-5c82-ac50-a988df90b415"));
        assertThat(prepared.restaurantId()).isEqualTo(UUID.fromString("d4579042-8239-57df-b716-c27d3a6c213a"));
        assertThat(prepared.menuVersionId()).isEqualTo(UUID.fromString("db0d6a46-9145-55c7-af56-2795b510fa19"));
        assertThat(prepared.categoryIds()).containsEntry(
                "rolls", UUID.fromString("42c9b27a-bea6-5ded-8e60-0ed3785467dd"));
        assertThat(prepared.itemIds()).hasSize(23);
        ArgumentCaptor<AssetImportRequest> request = ArgumentCaptor.forClass(AssetImportRequest.class);
        verify(assets).validate(request.capture());
        assertThat(request.getValue().content()).hasSize(43728);
        verify(assets, never()).validateAndStore(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsSelfConsistentBundleEditsNamespaceMutationAndSwappedAssetBeforeAssetAccess(
            @TempDir Path temporary) throws IOException {
        for (String mutated : new String[] {
                Files.readString(BUNDLE).replace("25000", "25100"),
                Files.readString(BUNDLE).replace(
                        StambulBundleLoader.APPROVED_UUID_NAMESPACE.toString(),
                        "00000000-0000-4000-8000-000000000001"),
                Files.readString(BUNDLE).replace(
                        "Электронное меню/restaurants/stambul/branding/logo.png",
                        "Электронное меню/restaurants/adamas/branding/hero-burger.png")
        }) {
            Path changed = Files.createTempFile(temporary, "changed-bundle-", ".json");
            Files.writeString(changed, mutated);
            AssetImporter assets = mock(AssetImporter.class);
            assertThatThrownBy(() -> new StambulBundleLoader(new ObjectMapper(), assets)
                    .load(DataImportRequest.localTest(changed, ROOT)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("digest differs");
            verifyNoInteractions(assets);
        }
    }

    @Test
    void rejectsLexicalEscapeAndSymlinkComponents(@TempDir Path temporary) throws IOException {
        Path root = temporary.resolve("root");
        Files.createDirectories(root);
        assertThatThrownBy(() -> StambulBundleLoader.safeResolve(root.toRealPath(), "../outside"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");

        Path outside = temporary.resolve("outside");
        Files.createDirectories(outside.resolve("restaurants/stambul"));
        Files.writeString(outside.resolve("restaurants/stambul/config.js"), "outside");
        Path link = root.resolve("Электронное меню");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getClass().getSimpleName());
        }
        assertThatThrownBy(() -> StambulBundleLoader.safeResolve(
                root.toRealPath(),
                "Электронное меню/restaurants/stambul/config.js"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void productionNeverFallsBackToLocalTimezoneUrlOrImplicitApprovals() {
        AssetImporter assets = acceptingAssetImporter();
        var missingProductionValues = new DataImportRequest(
                BUNDLE, ROOT, DataImportTarget.PRODUCTION, null, null, null,
                false, false, false, false);

        assertThatThrownBy(() -> new StambulBundleLoader(new ObjectMapper(), assets)
                .load(missingProductionValues))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Production timezone override is required");

        var localUrlIsForbidden = new DataImportRequest(
                BUNDLE,
                ROOT,
                DataImportTarget.PRODUCTION,
                "Europe/Moscow",
                URI.create("https://stambul.localhost"),
                "approval:test-only",
                true,
                true,
                true,
                true);
        assertThatThrownBy(() -> new StambulBundleLoader(new ObjectMapper(), assets)
                .load(localUrlIsForbidden))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot use a local/test host");
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("AGENTS.md"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("Repository root was not found");
        }
        return candidate;
    }

    private static AssetImporter acceptingAssetImporter() {
        AssetImporter assets = mock(AssetImporter.class);
        when(assets.validate(org.mockito.ArgumentMatchers.any())).thenReturn(new AssetImportReceipt(
                AssetKind.LOGO,
                LOGO_KEY,
                "image/png",
                43728,
                "90374c21b9cd4c15ef5b861521d0e5bcdc4571c3fae6f4aebd5c3927bfb6ee9b"));
        return assets;
    }
}
