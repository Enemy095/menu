package com.salamu.menu.menu.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PublicMenuRepresentationTagTests {

    @Test
    void identicalPublicRepresentationHasAStableStrongTag() {
        var first = PublicMenuRepresentationTag.etag("stambul", menu());
        var second = PublicMenuRepresentationTag.etag("stambul", menu());

        assertThat(first)
                .isEqualTo(second)
                .matches("\\\"menu-stambul-v7-[0-9a-f]{64}\\\"");
    }

    @Test
    void everyPublicRepresentationMutationChangesTheTag() {
        var original = menu();
        var originalTag = PublicMenuRepresentationTag.etag("stambul", original);

        var itemChanged = new PublicMenuResponse(
                original.restaurantId(), original.version(), original.publishedAt(), original.currency(),
                original.categories(),
                List.of(item("Changed item", original.items().getFirst().imageUrl(), true)));
        var categoryChanged = new PublicMenuResponse(
                original.restaurantId(), original.version(), original.publishedAt(), original.currency(),
                List.of(new PublicMenuResponse.Category("pizza", "Changed category", null, null, 10)),
                original.items());
        var assetChanged = new PublicMenuResponse(
                original.restaurantId(), original.version(), original.publishedAt(), original.currency(),
                original.categories(),
                List.of(item("Pepperoni", URI.create("https://assets.example.test/changed.jpg"), true)));
        var availabilityChanged = new PublicMenuResponse(
                original.restaurantId(), original.version(), original.publishedAt(), original.currency(),
                original.categories(),
                List.of(item("Pepperoni", original.items().getFirst().imageUrl(), false)));
        var currencyChanged = new PublicMenuResponse(
                original.restaurantId(), original.version(), original.publishedAt(), "USD",
                original.categories(), original.items());

        assertThat(List.of(itemChanged, categoryChanged, assetChanged, availabilityChanged, currencyChanged))
                .allSatisfy(changed -> assertThat(PublicMenuRepresentationTag.etag("stambul", changed))
                        .isNotEqualTo(originalTag));
    }

    private static PublicMenuResponse menu() {
        return new PublicMenuResponse(
                UUID.fromString("11000000-0000-4000-8000-000000000001"),
                7,
                Instant.parse("2026-07-14T09:00:00Z"),
                "RUB",
                List.of(new PublicMenuResponse.Category("pizza", "Pizza", null, null, 10)),
                List.of(item("Pepperoni", URI.create("https://assets.example.test/pizza.jpg"), true)));
    }

    private static PublicMenuResponse.Item item(String name, URI imageUrl, boolean available) {
        return new PublicMenuResponse.Item(
                "pizza-pepperoni", "pizza", name, null, 59_000, imageUrl, available, 10);
    }
}
