package com.salamu.menu.menu.internal.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.salamu.menu.menu.internal.application.PublishedMenu;

/** OpenAPI-compatible HTTP response; optional values are omitted rather than serialized as null. */
public record PublicMenuResponse(
        UUID restaurantId,
        long version,
        Instant publishedAt,
        String currency,
        List<Category> categories,
        List<Item> items) {

    static PublicMenuResponse from(PublishedMenu menu) {
        var categories = menu.categories().stream()
                .map(category -> new Category(
                        category.categoryId(),
                        category.name(),
                        category.description(),
                        category.imageUrl(),
                        category.sortOrder()))
                .toList();
        var items = menu.items().stream()
                .map(item -> new Item(
                        item.menuItemId(),
                        item.categoryId(),
                        item.name(),
                        item.description(),
                        item.priceMinor(),
                        item.imageUrl(),
                        item.available(),
                        item.sortOrder()))
                .toList();
        return new PublicMenuResponse(
                menu.restaurantId(),
                menu.version(),
                menu.publishedAt(),
                menu.currency(),
                categories,
                items);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Category(
            String categoryId,
            String name,
            String description,
            URI imageUrl,
            int sortOrder) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String menuItemId,
            String categoryId,
            String name,
            String description,
            long priceMinor,
            URI imageUrl,
            boolean available,
            int sortOrder) {
    }
}
