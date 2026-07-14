package com.salamu.menu.menu.internal.application;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable application read model; it is deliberately separate from HTTP and persistence models. */
public record PublishedMenu(
        UUID restaurantId,
        long version,
        Instant publishedAt,
        String currency,
        List<Category> categories,
        List<Item> items) {

    public PublishedMenu {
        categories = List.copyOf(categories);
        items = List.copyOf(items);
    }

    public record Category(
            String categoryId,
            String name,
            String description,
            URI imageUrl,
            int sortOrder) {
    }

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
