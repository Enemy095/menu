package com.salamu.menu.menu.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.salamu.menu.tenancy.api.TenantId;

/**
 * Complete immutable published-menu specification used by the non-HTTP provisioning boundary.
 * Collection order is semantic and must match the stable database read order exactly.
 */
public record PublishedMenuSnapshot(
        TenantId tenantId,
        UUID restaurantId,
        long version,
        Instant publishedAt,
        String currency,
        List<Category> categories,
        List<Item> items) {

    private static final int MAX_CATEGORIES = 500;
    private static final int MAX_ITEMS = 10_000;
    private static final Pattern PUBLIC_ID = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public PublishedMenuSnapshot {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(restaurantId, "restaurantId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        requireCurrency(currency);
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (!publishedAt.equals(publishedAt.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("publishedAt must have PostgreSQL microsecond precision");
        }

        categories = immutable(categories, "categories");
        items = immutable(items, "items");
        if (categories.isEmpty() || categories.size() > MAX_CATEGORIES) {
            throw new IllegalArgumentException("categories must contain between 1 and " + MAX_CATEGORIES + " entries");
        }
        if (items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("items must contain between 1 and " + MAX_ITEMS + " entries");
        }
        validateCollections(currency, categories, items);
    }

    private static void validateCollections(String menuCurrency, List<Category> categories, List<Item> items) {
        var categoryIds = new HashSet<String>();
        var categoryOrder = new HashMap<String, Integer>();
        Category previousCategory = null;
        for (int index = 0; index < categories.size(); index++) {
            var category = Objects.requireNonNull(categories.get(index), "category");
            if (!categoryIds.add(category.categoryId())) {
                throw new IllegalArgumentException("category IDs must be unique");
            }
            if (previousCategory != null && Category.ORDER.compare(previousCategory, category) >= 0) {
                throw new IllegalArgumentException("categories must be in strict canonical order");
            }
            categoryOrder.put(category.categoryId(), index);
            previousCategory = category;
        }

        var itemIds = new HashSet<String>();
        Item previousItem = null;
        for (var item : items) {
            Objects.requireNonNull(item, "item");
            if (!itemIds.add(item.menuItemId())) {
                throw new IllegalArgumentException("menu item IDs must be unique");
            }
            if (!categoryOrder.containsKey(item.categoryId())) {
                throw new IllegalArgumentException("menu item references an unknown category");
            }
            if (!menuCurrency.equals(item.currency())) {
                throw new IllegalArgumentException("menu item currency must match menu currency");
            }
            if (previousItem != null && compareItems(previousItem, item, categoryOrder) >= 0) {
                throw new IllegalArgumentException("items must be in strict canonical order");
            }
            previousItem = item;
        }
    }

    private static int compareItems(Item left, Item right, Map<String, Integer> categoryOrder) {
        int categoryComparison = Integer.compare(categoryOrder.get(left.categoryId()), categoryOrder.get(right.categoryId()));
        if (categoryComparison != 0) {
            return categoryComparison;
        }
        int sortComparison = Integer.compare(left.sortOrder(), right.sortOrder());
        return sortComparison != 0 ? sortComparison : left.menuItemId().compareTo(right.menuItemId());
    }

    private static <T> List<T> immutable(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        return List.copyOf(new ArrayList<>(values));
    }

    private static void requirePublicId(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > 120 || !PUBLIC_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is not a canonical public ID");
        }
    }

    private static void requireText(String value, int maxLength, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
    }

    private static void requireOptionalText(String value, int maxLength, String name) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
    }

    private static void requireCurrency(String value) {
        Objects.requireNonNull(value, "currency");
        if (!CURRENCY.matcher(value).matches()) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code");
        }
        try {
            Currency.getInstance(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code");
        }
    }

    public record Category(
            String categoryId,
            String name,
            String description,
            MenuAssetReference imageAsset,
            int sortOrder,
            boolean available) {

        private static final Comparator<Category> ORDER = Comparator
                .comparingInt(Category::sortOrder)
                .thenComparing(Category::categoryId);

        public Category {
            requirePublicId(categoryId, "categoryId");
            requireText(name, 120, "category name");
            requireOptionalText(description, 500, "category description");
            if (sortOrder < 0) {
                throw new IllegalArgumentException("category sortOrder must be non-negative");
            }
        }
    }

    public record Item(
            String menuItemId,
            String categoryId,
            String name,
            String description,
            long priceMinor,
            String currency,
            MenuAssetReference imageAsset,
            boolean available,
            int sortOrder) {

        public Item {
            requirePublicId(menuItemId, "menuItemId");
            requirePublicId(categoryId, "categoryId");
            requireText(name, 160, "item name");
            requireOptionalText(description, 1000, "item description");
            requireCurrency(currency);
            if (priceMinor < 0) {
                throw new IllegalArgumentException("priceMinor must be non-negative");
            }
            if (sortOrder < 0) {
                throw new IllegalArgumentException("item sortOrder must be non-negative");
            }
        }
    }
}
