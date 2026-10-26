package com.salamu.menu.menu.internal.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.salamu.menu.menu.api.PublishedMenuSnapshot;

record PublicMenuResponse(
        UUID restaurantId,
        long version,
        Instant publishedAt,
        String currency,
        List<Category> categories,
        List<Item> items) {

    static PublicMenuResponse from(PublishedMenuSnapshot menu) {
        var categories = menu.categories().stream()
                .map(category -> new Category(
                        category.publicId(),
                        category.name(),
                        category.description(),
                        category.imageUrl(),
                        category.sortOrder()))
                .toList();
        var items = menu.items().stream()
                .map(item -> new Item(
                        item.publicId(),
                        item.categoryPublicId(),
                        item.name(),
                        item.description(),
                        item.priceMinor(),
                        item.imageUrl(),
                        item.available(),
                        item.sortOrder(),
                        item.modifierGroups().stream()
                                .map(group -> new ModifierGroup(
                                        group.publicId(),
                                        group.name(),
                                        group.minSelections(),
                                        group.maxSelections(),
                                        group.sortOrder(),
                                        group.options().stream()
                                                .map(option -> new ModifierOption(
                                                        option.publicId(),
                                                        option.name(),
                                                        option.priceDeltaMinor(),
                                                        option.available(),
                                                        option.sortOrder()))
                                                .toList()))
                                .toList()))
                .toList();
        return new PublicMenuResponse(
                menu.restaurantId().value(),
                menu.version(),
                menu.publishedAt(),
                menu.currency(),
                categories,
                items);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Category(String categoryId, String name, String description, URI imageUrl, int sortOrder) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Item(
            String menuItemId,
            String categoryId,
            String name,
            String description,
            long priceMinor,
            URI imageUrl,
            boolean available,
            int sortOrder,
            List<ModifierGroup> modifierGroups) {
    }

    record ModifierGroup(
            String modifierGroupId,
            String name,
            int minSelections,
            int maxSelections,
            int sortOrder,
            List<ModifierOption> options) {
    }

    record ModifierOption(
            String optionId,
            String name,
            long priceDeltaMinor,
            boolean available,
            int sortOrder) {
    }
}
