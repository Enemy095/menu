package com.salamu.menu.asset.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.salamu.menu.asset.api.AssetId;
import com.salamu.menu.asset.api.AssetKind;
import com.salamu.menu.asset.api.AssetUnavailableException;
import com.salamu.menu.asset.api.PublicAsset;

class AssetCatalogServiceTests {

    private static final AssetSha256 SHA = new AssetSha256("a".repeat(64));

    private AssetMetadataRepository repository;
    private AssetPublicUrlResolver urlResolver;
    private AssetCatalogService catalog;

    @BeforeEach
    void setUp() {
        repository = mock(AssetMetadataRepository.class);
        urlResolver = mock(AssetPublicUrlResolver.class);
        catalog = new AssetCatalogService(repository, urlResolver);
        when(urlResolver.resolve(any())).thenAnswer(invocation ->
                URI.create("https://cdn.example.test/" + invocation.<AssetObjectKey>getArgument(0).value()));
    }

    @Test
    void emptyBatchIsImmutableAndDoesNotAccessPersistence() {
        Map<AssetId, PublicAsset> result = catalog.requireActive(List.of());

        assertThat(result).isEmpty();
        assertThatThrownBy(() -> result.put(id(1), publicAsset(id(1))))
                .isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(repository, urlResolver);
    }

    @Test
    void duplicateIdsAreCollapsedIntoOneBoundedBatchQuery() {
        AssetId first = id(1);
        AssetId second = id(2);
        when(repository.findAllActiveByIds(any())).thenReturn(List.of(record(first), record(second)));

        Map<AssetId, PublicAsset> result = catalog.requireActive(List.of(first, second, first, second));

        verify(repository).findAllActiveByIds(argThat(requested -> requested.equals(Set.of(first, second))));
        assertThat(result.keySet()).containsExactly(first, second);
        assertThatThrownBy(() -> result.remove(first)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resolvesExactlyFiveHundredUniqueIdsInOneQuery() {
        List<AssetId> ids = ids(500);
        when(repository.findAllActiveByIds(any())).thenAnswer(invocation ->
                invocation.<Set<AssetId>>getArgument(0).stream().map(this::record).toList());

        Map<AssetId, PublicAsset> result = catalog.requireActive(ids);

        assertThat(result).hasSize(500);
        assertThat(result.keySet()).containsExactlyElementsOf(ids);
        verify(repository).findAllActiveByIds(any());
    }

    @Test
    void rejectsMoreThanFiveHundredUniqueIdsBeforePersistence() {
        assertThatThrownBy(() -> catalog.requireActive(ids(501)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("At most 500 unique asset IDs can be resolved at once");

        verifyNoInteractions(repository);
    }

    @Test
    void missingDeletedDuplicateOrUnexpectedRowsFailClosed() {
        AssetId requested = id(1);
        AssetId unexpected = id(2);

        when(repository.findAllActiveByIds(any())).thenReturn(List.of());
        assertUnavailable(() -> catalog.requireActive(requested));

        when(repository.findAllActiveByIds(any())).thenReturn(List.of(record(requested, AssetStatus.DELETED)));
        assertUnavailable(() -> catalog.requireActive(requested));

        when(repository.findAllActiveByIds(any())).thenReturn(List.of(record(requested), record(requested)));
        assertUnavailable(() -> catalog.requireActive(requested));

        when(repository.findAllActiveByIds(any())).thenReturn(List.of(record(requested), record(unexpected)));
        assertUnavailable(() -> catalog.requireActive(requested));
    }

    @Test
    void publicResultContainsNoPersistenceOrStorageMetadata() {
        AssetId id = id(1);
        when(repository.findAllActiveByIds(any())).thenReturn(List.of(record(id)));

        PublicAsset asset = catalog.requireActive(id);

        assertThat(asset.id()).isEqualTo(id);
        assertThat(asset.kind()).isEqualTo(AssetKind.LOGO);
        assertThat(asset.sha256()).isEqualTo(SHA.value());
        assertThat(asset.publicUrl()).isEqualTo(
                URI.create("https://cdn.example.test/assets/logo/" + SHA.value() + ".png"));
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AssetUnavailableException.class)
                .hasMessage("Required asset is unavailable")
                .hasMessageNotContaining(id(1).value().toString())
                .hasMessageNotContaining("assets/");
    }

    private AssetRecord record(AssetId id) {
        return record(id, AssetStatus.ACTIVE);
    }

    private AssetRecord record(AssetId id, AssetStatus status) {
        return new AssetRecord(
                id,
                AssetKind.LOGO,
                AssetObjectKey.canonical(AssetKind.LOGO, SHA, "png"),
                "image/png",
                8,
                SHA,
                status,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static PublicAsset publicAsset(AssetId id) {
        return new PublicAsset(
                id,
                AssetKind.LOGO,
                URI.create("https://cdn.example.test/assets/logo/" + SHA.value() + ".png"),
                SHA.value());
    }

    private static AssetId id(int number) {
        return new AssetId(new UUID(0, number));
    }

    private static List<AssetId> ids(int count) {
        List<AssetId> ids = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            ids.add(id(index));
        }
        return ids;
    }
}
