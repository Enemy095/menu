package com.salamu.menu.menu.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.salamu.menu.asset.api.AssetCatalog;
import com.salamu.menu.asset.api.AssetId;
import com.salamu.menu.asset.api.AssetKind;
import com.salamu.menu.asset.api.PublicAsset;
import com.salamu.menu.menu.api.MenuItemId;
import com.salamu.menu.menu.api.MenuVersionId;
import com.salamu.menu.menu.api.PublishedMenuNotFoundException;
import com.salamu.menu.restaurant.api.DefaultRestaurantQuery;
import com.salamu.menu.restaurant.api.RestaurantId;
import com.salamu.menu.restaurant.api.RestaurantSnapshot;
import com.salamu.menu.restaurant.api.RestaurantStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PublishedMenuQueryServiceTests {

    private static final RestaurantId RESTAURANT_ID =
            id(RestaurantId::new, "10000000-0000-4000-8000-000000000001");
    private static final MenuVersionId VERSION_ID =
            id(MenuVersionId::new, "20000000-0000-4000-8000-000000000002");
    private static final MenuItemId ITEM_ID = id(MenuItemId::new, "30000000-0000-4000-8000-000000000003");
    private static final UUID CATEGORY_ID = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID GROUP_ID = UUID.fromString("50000000-0000-4000-8000-000000000005");
    private static final AssetId CATEGORY_ASSET = id(AssetId::new, "60000000-0000-4000-8000-000000000006");
    private static final AssetId ITEM_ASSET = id(AssetId::new, "70000000-0000-4000-8000-000000000007");

    private DefaultRestaurantQuery restaurantQuery;
    private PublishedMenuReadRepository repository;
    private AssetCatalog assetCatalog;
    private PublishedMenuQueryService service;

    @BeforeEach
    void setUp() {
        restaurantQuery = mock(DefaultRestaurantQuery.class);
        repository = mock(PublishedMenuReadRepository.class);
        assetCatalog = mock(AssetCatalog.class);
        service = new PublishedMenuQueryService(restaurantQuery, repository, assetCatalog);

        when(restaurantQuery.requireActiveDefault()).thenReturn(new RestaurantSnapshot(
                RESTAURANT_ID, "main", "Main restaurant", RestaurantStatus.ACTIVE));
        when(repository.findPublished(RESTAURANT_ID)).thenReturn(List.of(header()));
        when(repository.findCategories(VERSION_ID)).thenReturn(List.of(category()));
        when(repository.findItems(VERSION_ID)).thenReturn(List.of(item("RUB", ITEM_ASSET)));
        when(repository.findModifierGroups(VERSION_ID)).thenReturn(List.of(group()));
        when(repository.findModifierOptions(VERSION_ID)).thenReturn(List.of(
                option("cheese", -150, false, 5),
                option("double-cheese", 250, true, 10)));
        when(assetCatalog.requireActive(any(java.util.Collection.class))).thenReturn(Map.of(
                CATEGORY_ASSET, asset(CATEGORY_ASSET, AssetKind.CATEGORY, "category"),
                ITEM_ASSET, asset(ITEM_ASSET, AssetKind.MENU_ITEM, "item")));
    }

    @Test
    void loadsTypedOrderedSnapshotWithFiveMenuReadsAndOneAssetBatch() {
        var menu = service.current();

        assertThat(menu.restaurantId()).isEqualTo(RESTAURANT_ID);
        assertThat(menu.menuVersionId()).isEqualTo(VERSION_ID);
        assertThat(menu.version()).isEqualTo(7);
        assertThat(menu.currency()).isEqualTo("RUB");
        assertThat(menu.representationHash()).matches("[0-9a-f]{64}");
        assertThat(menu.categories()).singleElement().satisfies(category -> {
            assertThat(category.publicId()).isEqualTo("pizza");
            assertThat(category.imageUrl()).hasToString("https://cdn.example.test/category.jpg");
        });
        assertThat(menu.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(ITEM_ID);
            assertThat(item.publicId()).isEqualTo("pepperoni");
            assertThat(item.imageUrl()).hasToString("https://cdn.example.test/item.jpg");
            assertThat(item.modifierGroups()).singleElement().satisfies(group -> {
                assertThat(group.publicId()).isEqualTo("cheese-choice");
                assertThat(group.minSelections()).isZero();
                assertThat(group.maxSelections()).isEqualTo(2);
                assertThat(group.options()).extracting(option -> option.publicId())
                        .containsExactly("cheese", "double-cheese");
                assertThat(group.options()).extracting(option -> option.priceDeltaMinor())
                        .containsExactly(-150L, 250L);
                assertThat(group.options()).extracting(option -> option.available())
                        .containsExactly(false, true);
            });
        });

        verify(repository).findPublished(RESTAURANT_ID);
        verify(repository).findCategories(VERSION_ID);
        verify(repository).findItems(VERSION_ID);
        verify(repository).findModifierGroups(VERSION_ID);
        verify(repository).findModifierOptions(VERSION_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<AssetId>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(assetCatalog).requireActive(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrderElementsOf(Set.of(CATEGORY_ASSET, ITEM_ASSET));
    }

    @Test
    void supportsEmptyPublishedMenuAndStillUsesTheSingleEmptyAssetBatch() {
        when(repository.findCategories(VERSION_ID)).thenReturn(List.of());
        when(repository.findItems(VERSION_ID)).thenReturn(List.of());
        when(repository.findModifierGroups(VERSION_ID)).thenReturn(List.of());
        when(repository.findModifierOptions(VERSION_ID)).thenReturn(List.of());
        when(assetCatalog.requireActive(any(java.util.Collection.class))).thenReturn(Map.of());

        var menu = service.current();

        assertThat(menu.currency()).isEqualTo("RUB");
        assertThat(menu.categories()).isEmpty();
        assertThat(menu.items()).isEmpty();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<AssetId>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(assetCatalog).requireActive(ids.capture());
        assertThat(ids.getValue()).isEmpty();
    }

    @Test
    void absenceIsTheOnlyConditionReportedAsMenuNotFound() {
        when(repository.findPublished(RESTAURANT_ID)).thenReturn(List.of());

        assertThatThrownBy(service::current)
                .isInstanceOf(PublishedMenuNotFoundException.class)
                .hasMessageContaining("404");
        verify(repository, never()).findCategories(any());
        verify(assetCatalog, never()).requireActive(any(java.util.Collection.class));
    }

    @Test
    void mixedItemCurrencyFailsAsGenericCorruption() {
        when(repository.findItems(VERSION_ID)).thenReturn(List.of(item("USD", ITEM_ASSET)));

        assertThatThrownBy(service::current)
                .isInstanceOf(InvalidPublishedMenuException.class)
                .hasMessage("Published menu is internally inconsistent")
                .hasMessageNotContaining("USD")
                .hasMessageNotContaining(ITEM_ID.value().toString());
    }

    @Test
    void wrongAssetKindFailsClosed() {
        when(assetCatalog.requireActive(any(java.util.Collection.class))).thenReturn(Map.of(
                CATEGORY_ASSET, asset(CATEGORY_ASSET, AssetKind.MENU_ITEM, "category"),
                ITEM_ASSET, asset(ITEM_ASSET, AssetKind.MENU_ITEM, "item")));

        assertThatThrownBy(service::current)
                .isInstanceOf(InvalidPublishedMenuException.class)
                .hasMessageNotContaining(CATEGORY_ASSET.value().toString())
                .hasMessageNotContaining("CATEGORY");
    }

    @Test
    void groupWithoutOptionsAndMoreThanTwentyGroupsFailClosed() {
        when(repository.findModifierOptions(VERSION_ID)).thenReturn(List.of());
        assertThatThrownBy(service::current).isInstanceOf(InvalidPublishedMenuException.class);

        var groups = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> new PublishedMenuReadRepository.ModifierGroupRow(
                        new UUID(10, index + 1),
                        ITEM_ID,
                        "group-" + index,
                        "Group " + index,
                        0,
                        1,
                        index))
                .toList();
        when(repository.findModifierGroups(VERSION_ID)).thenReturn(groups);

        assertThatThrownBy(service::current).isInstanceOf(InvalidPublishedMenuException.class);
    }

    private static PublishedMenuReadRepository.MenuHeader header() {
        return new PublishedMenuReadRepository.MenuHeader(
                VERSION_ID,
                RESTAURANT_ID,
                7,
                Instant.parse("2026-07-14T09:00:00Z"),
                "RUB");
    }

    private static PublishedMenuReadRepository.CategoryRow category() {
        return new PublishedMenuReadRepository.CategoryRow(
                CATEGORY_ID, "pizza", "Pizza", null, CATEGORY_ASSET, 10);
    }

    private static PublishedMenuReadRepository.ItemRow item(String currency, AssetId assetId) {
        return new PublishedMenuReadRepository.ItemRow(
                ITEM_ID,
                CATEGORY_ID,
                "pizza",
                "pepperoni",
                "Pepperoni",
                null,
                59_000,
                currency,
                assetId,
                true,
                10);
    }

    private static PublishedMenuReadRepository.ModifierGroupRow group() {
        return new PublishedMenuReadRepository.ModifierGroupRow(
                GROUP_ID, ITEM_ID, "cheese-choice", "Cheese", 0, 2, 10);
    }

    private static PublishedMenuReadRepository.ModifierOptionRow option(
            String publicId, long delta, boolean available, int sortOrder) {
        return new PublishedMenuReadRepository.ModifierOptionRow(
                UUID.nameUUIDFromBytes(publicId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                GROUP_ID,
                publicId,
                publicId,
                delta,
                available,
                sortOrder);
    }

    private static PublicAsset asset(AssetId id, AssetKind kind, String name) {
        return new PublicAsset(
                id,
                kind,
                URI.create("https://cdn.example.test/" + name + ".jpg"),
                "a".repeat(64));
    }

    private static <T> T id(java.util.function.Function<UUID, T> constructor, String value) {
        return constructor.apply(UUID.fromString(value));
    }
}
