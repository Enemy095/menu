package com.salamu.menu.menu.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.TenantAsset;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantAssetService;
import com.salamu.menu.tenancy.api.TenantContext;
import com.salamu.menu.tenancy.api.TenantId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PublicMenuQueryServiceTests {

    private static final TenantId TENANT_ID =
            new TenantId(UUID.fromString("10000000-0000-4000-8000-000000000001"));
    private static final TenantContext TENANT_CONTEXT = new TenantContext(TENANT_ID, "stambul");
    private static final UUID MENU_VERSION_ID =
            UUID.fromString("11000000-0000-4000-8000-000000000001");
    private static final PublicMenuReadRepository.MenuHeader HEADER =
            new PublicMenuReadRepository.MenuHeader(
                    MENU_VERSION_ID,
                    UUID.fromString("12000000-0000-4000-8000-000000000001"),
                    7,
                    Instant.parse("2026-07-14T09:00:00Z"),
                    "RUB");

    private PublicMenuReadRepository repository;
    private TenantAssetService assetService;
    private PublicMenuQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(PublicMenuReadRepository.class);
        assetService = mock(TenantAssetService.class);
        service = new PublicMenuQueryService(repository, assetService);
        when(repository.findCurrentPublished(TENANT_ID)).thenReturn(java.util.Optional.of(HEADER));
        when(repository.findCategories(TENANT_ID, MENU_VERSION_ID)).thenReturn(List.of(
                new PublicMenuReadRepository.MenuCategoryRow(
                        "category", "Category", null, null, 0)));
    }

    @Test
    void queryAndAssetBatchCountsStayConstantForTwoAndManyItems() {
        var twoItems = itemRows(2);
        var manyItems = itemRows(200);
        when(repository.findItems(TENANT_ID, MENU_VERSION_ID)).thenReturn(twoItems, manyItems);
        when(assetService.getActiveAssets(eq(TENANT_ID), anyCollection()))
                .thenAnswer(invocation -> assets(invocation.getArgument(1)));

        var first = service.getCurrentPublished(TENANT_CONTEXT);
        var second = service.getCurrentPublished(TENANT_CONTEXT);

        assertThat(first.items()).hasSize(2);
        assertThat(second.items()).hasSize(200);
        verify(repository, times(2)).findCurrentPublished(TENANT_ID);
        verify(repository, times(2)).findCategories(TENANT_ID, MENU_VERSION_ID);
        verify(repository, times(2)).findItems(TENANT_ID, MENU_VERSION_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<TenantAssetId>> batches = ArgumentCaptor.forClass(Collection.class);
        verify(assetService, times(2)).getActiveAssets(eq(TENANT_ID), batches.capture());
        assertThat(batches.getAllValues()).extracting(Collection::size).containsExactly(2, 200);
    }

    @Test
    void rejectsMixedCurrencyBeforeResolvingAssets() {
        when(repository.findItems(TENANT_ID, MENU_VERSION_ID)).thenReturn(List.of(
                new PublicMenuReadRepository.MenuItemRow(
                        "wrong-currency",
                        "pizza",
                        "Wrong currency",
                        null,
                        100,
                        "USD",
                        null,
                        true,
                        10)));

        assertThatThrownBy(() -> service.getCurrentPublished(TENANT_CONTEXT))
                .isInstanceOf(InvalidPublishedMenuException.class)
                .hasMessage("Published menu data is invalid");
        verifyNoInteractions(assetService);
    }

    @Test
    void failsClosedWhenThereIsNoCurrentPublishedMenu() {
        when(repository.findCurrentPublished(TENANT_ID)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getCurrentPublished(TENANT_CONTEXT))
                .isInstanceOf(PublishedMenuNotFoundException.class);
        verifyNoInteractions(assetService);
    }

    private static List<PublicMenuReadRepository.MenuItemRow> itemRows(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> {
                    var assetId = new TenantAssetId(new UUID(0, index + 1L));
                    return new PublicMenuReadRepository.MenuItemRow(
                            "item-" + index,
                            "category",
                            "Item " + index,
                            null,
                            index * 100L,
                            "RUB",
                            assetId,
                            index % 2 == 0,
                            index);
                })
                .toList();
    }

    private static Map<TenantAssetId, TenantAsset> assets(Collection<TenantAssetId> assetIds) {
        Map<TenantAssetId, TenantAsset> result = new LinkedHashMap<>();
        for (var assetId : assetIds) {
            result.put(assetId, new TenantAsset(
                    assetId,
                    TENANT_ID,
                    AssetKind.MENU_ITEM_IMAGE,
                    "image/jpeg",
                    1,
                    "a".repeat(64),
                    URI.create("https://assets.example.test/" + assetId.value())));
        }
        return Map.copyOf(result);
    }
}
