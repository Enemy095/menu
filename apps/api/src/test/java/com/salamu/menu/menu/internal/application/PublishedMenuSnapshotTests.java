package com.salamu.menu.menu.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.salamu.menu.menu.api.MenuItemId;
import com.salamu.menu.menu.api.PublishedMenuSnapshot;
import com.salamu.menu.restaurant.api.RestaurantId;

import org.junit.jupiter.api.Test;

class PublishedMenuSnapshotTests {

    private static final RestaurantId RESTAURANT_ID =
            new RestaurantId(UUID.fromString("10000000-0000-4000-8000-000000000001"));
    private static final MenuItemId ITEM_ID =
            new MenuItemId(UUID.fromString("20000000-0000-4000-8000-000000000002"));
    private static final Instant PUBLISHED_AT = Instant.parse("2026-07-14T09:00:00Z");

    @Test
    void representationHashIsStableAndCoversModifierAvailabilityAndSignedDelta() {
        var categories = List.of(category());
        var baselineItems = List.of(item(option(-100, false)));
        var baseline = hash(categories, baselineItems);

        assertThat(hash(categories, baselineItems)).isEqualTo(baseline);
        assertThat(hash(categories, List.of(item(option(-100, true))))).isNotEqualTo(baseline);
        assertThat(hash(categories, List.of(item(option(100, false))))).isNotEqualTo(baseline);
    }

    @Test
    void rejectsContractBoundViolations() {
        assertThatThrownBy(() -> new PublishedMenuSnapshot.ModifierGroup(
                        "size", "Size", 2, 1, 0, List.of(option(0, true))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublishedMenuSnapshot.ModifierGroup(
                        "size", "Size", 0, 1, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublishedMenuSnapshot.ModifierOption(
                        "large", "Large", 1_000_000_000_001L, true, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublishedMenuSnapshot.Item(
                        ITEM_ID,
                        "pepperoni",
                        "pizza",
                        "Pepperoni",
                        null,
                        1_000_000_000_001L,
                        "RUB",
                        null,
                        true,
                        0,
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String hash(
            List<PublishedMenuSnapshot.Category> categories,
            List<PublishedMenuSnapshot.Item> items) {
        return PublishedMenuRepresentationHasher.hash(
                RESTAURANT_ID, 7, PUBLISHED_AT, "RUB", categories, items);
    }

    private static PublishedMenuSnapshot.Category category() {
        return new PublishedMenuSnapshot.Category("pizza", "Pizza", null, null, 0);
    }

    private static PublishedMenuSnapshot.Item item(PublishedMenuSnapshot.ModifierOption option) {
        return new PublishedMenuSnapshot.Item(
                ITEM_ID,
                "pepperoni",
                "pizza",
                "Pepperoni",
                null,
                59_000,
                "RUB",
                null,
                true,
                0,
                List.of(new PublishedMenuSnapshot.ModifierGroup(
                        "size", "Size", 0, 1, 0, List.of(option))));
    }

    private static PublishedMenuSnapshot.ModifierOption option(long delta, boolean available) {
        return new PublishedMenuSnapshot.ModifierOption("large", "Large", delta, available, 0);
    }
}
