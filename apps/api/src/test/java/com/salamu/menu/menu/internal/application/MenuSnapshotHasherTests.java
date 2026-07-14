package com.salamu.menu.menu.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.salamu.menu.menu.api.MenuAssetReference;
import com.salamu.menu.menu.api.PublishedMenuSnapshot;
import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantId;

import org.junit.jupiter.api.Test;

class MenuSnapshotHasherTests {

    private static final TenantId TENANT_ID = new TenantId(
            UUID.fromString("10000000-0000-4000-8000-000000000001"));
    private static final UUID RESTAURANT_ID = UUID.fromString("11000000-0000-4000-8000-000000000001");
    private static final TenantAssetId ASSET_ID = new TenantAssetId(
            UUID.fromString("fa000000-0000-4000-8000-000000000001"));
    private static final ProvisioningRunFingerprint RUN = new ProvisioningRunFingerprint("a".repeat(64));

    @Test
    void canonicalHashIsStableOrderSensitiveAndIncludesEverySemanticField() {
        var snapshot = snapshot("Первый");
        var identical = snapshot("Первый");
        var changed = snapshot("Изменённый");
        var assetContentChanged = snapshot("Первый", "b".repeat(64));

        assertThat(MenuSnapshotHasher.hash(identical)).isEqualTo(MenuSnapshotHasher.hash(snapshot));
        assertThat(MenuSnapshotHasher.hash(changed)).isNotEqualTo(MenuSnapshotHasher.hash(snapshot));
        assertThat(MenuSnapshotHasher.hash(assetContentChanged)).isNotEqualTo(MenuSnapshotHasher.hash(snapshot));

        var ids = DeterministicMenuIds.forSnapshot(snapshot, RUN);
        assertThat(DeterministicMenuIds.forSnapshot(identical, RUN)).isEqualTo(ids);
        assertThat(DeterministicMenuIds.forSnapshot(snapshot, new ProvisioningRunFingerprint("b".repeat(64))))
                .isNotEqualTo(ids);
        assertThat(ids.categoryIds()).containsOnlyKeys("burgers", "rolls");
        assertThat(ids.itemIds()).containsOnlyKeys("burger", "roll");
    }

    @Test
    void snapshotDefensivelyCopiesCollectionsAndRejectsNonCanonicalOrMixedState() {
        var categories = new ArrayList<>(snapshot("Первый").categories());
        var items = new ArrayList<>(snapshot("Первый").items());
        var immutable = new PublishedMenuSnapshot(
                TENANT_ID,
                RESTAURANT_ID,
                1,
                Instant.parse("2026-07-01T00:00:00Z"),
                "RUB",
                categories,
                items);
        categories.clear();
        items.clear();

        assertThat(immutable.categories()).hasSize(2);
        assertThat(immutable.items()).hasSize(2);
        assertThatThrownBy(() -> immutable.categories().clear()).isInstanceOf(UnsupportedOperationException.class);

        var reverseCategories = immutable.categories().reversed();
        assertThatThrownBy(() -> new PublishedMenuSnapshot(
                        TENANT_ID,
                        RESTAURANT_ID,
                        1,
                        Instant.parse("2026-07-01T00:00:00Z"),
                        "RUB",
                        reverseCategories,
                        immutable.items()))
                .isInstanceOf(IllegalArgumentException.class);

        var mixedItems = List.of(
                new PublishedMenuSnapshot.Item("burger", "burgers", "Бургер", null, 100, "USD", null, true, 0),
                immutable.items().get(1));
        assertThatThrownBy(() -> new PublishedMenuSnapshot(
                        TENANT_ID,
                        RESTAURANT_ID,
                        1,
                        Instant.parse("2026-07-01T00:00:00Z"),
                        "RUB",
                        immutable.categories(),
                        mixedItems))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PublishedMenuSnapshot snapshot(String firstItemName) {
        return snapshot(firstItemName, "a".repeat(64));
    }

    private static PublishedMenuSnapshot snapshot(String firstItemName, String assetSha256) {
        return new PublishedMenuSnapshot(
                TENANT_ID,
                RESTAURANT_ID,
                1,
                Instant.parse("2026-07-01T00:00:00Z"),
                "RUB",
                List.of(
                        new PublishedMenuSnapshot.Category(
                                "burgers", "Бургеры", null,
                                new MenuAssetReference(ASSET_ID, assetSha256), 0, true),
                        new PublishedMenuSnapshot.Category("rolls", "Роллы", "Описание", null, 1, true)),
                List.of(
                        new PublishedMenuSnapshot.Item(
                                "burger", "burgers", firstItemName, null, 10_000, "RUB", null, true, 0),
                        new PublishedMenuSnapshot.Item(
                                "roll", "rolls", "Ролл", "Описание", 20_000, "RUB", null, false, 0)));
    }
}
