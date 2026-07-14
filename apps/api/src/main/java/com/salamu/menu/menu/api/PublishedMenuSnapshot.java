package com.salamu.menu.menu.api;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.salamu.menu.restaurant.api.RestaurantId;

/** Immutable, ordered public menu projection shared with ordering and the HTTP adapter. */
public record PublishedMenuSnapshot(
        RestaurantId restaurantId,
        MenuVersionId menuVersionId,
        long version,
        Instant publishedAt,
        String currency,
        List<Category> categories,
        List<Item> items,
        String representationHash) {

    public static final int MAX_CATEGORIES = 500;
    public static final int MAX_ITEMS = 10_000;
    public static final int MAX_MODIFIER_GROUPS_PER_ITEM = 20;
    public static final int MAX_OPTIONS_PER_GROUP = 100;
    public static final long MAX_MONEY_MINOR = 1_000_000_000_000L;

    private static final Pattern PUBLIC_ID = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    public PublishedMenuSnapshot {
        Objects.requireNonNull(restaurantId, "restaurantId");
        Objects.requireNonNull(menuVersionId, "menuVersionId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        requireCurrency(currency);
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        categories = immutable(categories, "categories");
        items = immutable(items, "items");
        if (categories.size() > MAX_CATEGORIES) {
            throw new IllegalArgumentException("too many categories");
        }
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("too many items");
        }
        if (representationHash == null || !SHA_256.matcher(representationHash).matches()) {
            throw new IllegalArgumentException("representationHash must be lowercase SHA-256");
        }
        validateGraph(currency, categories, items);
    }

    private static void validateGraph(String currency, List<Category> categories, List<Item> items) {
        Set<String> categoryPublicIds = new HashSet<>();
        Category previousCategory = null;
        for (Category category : categories) {
            Objects.requireNonNull(category, "category");
            if (!categoryPublicIds.add(category.publicId())) {
                throw new IllegalArgumentException("category public IDs must be unique");
            }
            if (previousCategory != null && compare(previousCategory, category) >= 0) {
                throw new IllegalArgumentException("categories must be in canonical order");
            }
            previousCategory = category;
        }

        Set<MenuItemId> itemIds = new HashSet<>();
        Set<String> itemPublicIds = new HashSet<>();
        int previousCategoryIndex = -1;
        Item previousItem = null;
        for (Item item : items) {
            Objects.requireNonNull(item, "item");
            if (!itemIds.add(item.id()) || !itemPublicIds.add(item.publicId())) {
                throw new IllegalArgumentException("menu item IDs must be unique");
            }
            int categoryIndex = indexOf(categories, item.categoryPublicId());
            if (categoryIndex < 0) {
                throw new IllegalArgumentException("menu item references an unknown category");
            }
            if (!currency.equals(item.currency())) {
                throw new IllegalArgumentException("menu item currency must match menu version currency");
            }
            if (categoryIndex < previousCategoryIndex
                    || (categoryIndex == previousCategoryIndex
                            && previousItem != null
                            && compare(previousItem, item) >= 0)) {
                throw new IllegalArgumentException("menu items must be in canonical order");
            }
            previousCategoryIndex = categoryIndex;
            previousItem = item;
        }
    }

    private static int indexOf(List<Category> categories, String publicId) {
        for (int index = 0; index < categories.size(); index++) {
            if (categories.get(index).publicId().equals(publicId)) {
                return index;
            }
        }
        return -1;
    }

    private static int compare(Category left, Category right) {
        int order = Integer.compare(left.sortOrder(), right.sortOrder());
        return order != 0 ? order : left.publicId().compareTo(right.publicId());
    }

    private static int compare(Item left, Item right) {
        int order = Integer.compare(left.sortOrder(), right.sortOrder());
        return order != 0 ? order : left.publicId().compareTo(right.publicId());
    }

    private static <T> List<T> immutable(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        return List.copyOf(new ArrayList<>(values));
    }

    private static void requirePublicId(String value, String name) {
        if (value == null || value.length() > 120 || !PUBLIC_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a canonical public ID");
        }
    }

    private static void requireText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
    }

    private static void requireOptionalText(String value, int maxLength, String name) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
    }

    private static void requireCurrency(String value) {
        if (value == null || !CURRENCY.matcher(value).matches()) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code");
        }
        Currency.getInstance(value);
    }

    private static void requireSortOrder(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void requireSafeAssetUrl(URI value) {
        if (value != null
                && (!value.isAbsolute()
                        || value.getHost() == null
                        || !("https".equalsIgnoreCase(value.getScheme())
                                || "http".equalsIgnoreCase(value.getScheme()))
                        || value.getUserInfo() != null
                        || value.getQuery() != null
                        || value.getFragment() != null)) {
            throw new IllegalArgumentException("imageUrl must be a safe absolute HTTP(S) URL");
        }
    }

    public record Category(String publicId, String name, String description, URI imageUrl, int sortOrder) {

        public Category {
            requirePublicId(publicId, "category publicId");
            requireText(name, 120, "category name");
            requireOptionalText(description, 500, "category description");
            requireSafeAssetUrl(imageUrl);
            requireSortOrder(sortOrder, "category sortOrder");
        }
    }

    public record Item(
            MenuItemId id,
            String publicId,
            String categoryPublicId,
            String name,
            String description,
            long priceMinor,
            String currency,
            URI imageUrl,
            boolean available,
            int sortOrder,
            List<ModifierGroup> modifierGroups) {

        public Item {
            Objects.requireNonNull(id, "id");
            requirePublicId(publicId, "item publicId");
            requirePublicId(categoryPublicId, "category publicId");
            requireText(name, 160, "item name");
            requireOptionalText(description, 1000, "item description");
            if (priceMinor < 0 || priceMinor > MAX_MONEY_MINOR) {
                throw new IllegalArgumentException("priceMinor is outside accepted bounds");
            }
            requireCurrency(currency);
            requireSafeAssetUrl(imageUrl);
            requireSortOrder(sortOrder, "item sortOrder");
            modifierGroups = immutable(modifierGroups, "modifierGroups");
            if (modifierGroups.size() > MAX_MODIFIER_GROUPS_PER_ITEM) {
                throw new IllegalArgumentException("too many modifier groups");
            }
            validateModifierGroups(modifierGroups);
        }

        private static void validateModifierGroups(List<ModifierGroup> groups) {
            Set<String> publicIds = new HashSet<>();
            ModifierGroup previous = null;
            for (ModifierGroup group : groups) {
                Objects.requireNonNull(group, "modifierGroup");
                if (!publicIds.add(group.publicId())) {
                    throw new IllegalArgumentException("modifier group IDs must be unique per item");
                }
                if (previous != null && compare(previous, group) >= 0) {
                    throw new IllegalArgumentException("modifier groups must be in canonical order");
                }
                previous = group;
            }
        }

        private static int compare(ModifierGroup left, ModifierGroup right) {
            int order = Integer.compare(left.sortOrder(), right.sortOrder());
            return order != 0 ? order : left.publicId().compareTo(right.publicId());
        }
    }

    public record ModifierGroup(
            String publicId,
            String name,
            int minSelections,
            int maxSelections,
            int sortOrder,
            List<ModifierOption> options) {

        public ModifierGroup {
            requirePublicId(publicId, "modifier group publicId");
            requireText(name, 120, "modifier group name");
            if (minSelections < 0 || minSelections > 99
                    || maxSelections < 1 || maxSelections > 99
                    || minSelections > maxSelections) {
                throw new IllegalArgumentException("modifier selection bounds are invalid");
            }
            requireSortOrder(sortOrder, "modifier group sortOrder");
            options = immutable(options, "options");
            if (options.isEmpty() || options.size() > MAX_OPTIONS_PER_GROUP) {
                throw new IllegalArgumentException("modifier group must contain 1 to 100 options");
            }
            Set<String> publicIds = new HashSet<>();
            ModifierOption previous = null;
            for (ModifierOption option : options) {
                Objects.requireNonNull(option, "modifierOption");
                if (!publicIds.add(option.publicId())) {
                    throw new IllegalArgumentException("modifier option IDs must be unique per group");
                }
                if (previous != null && compare(previous, option) >= 0) {
                    throw new IllegalArgumentException("modifier options must be in canonical order");
                }
                previous = option;
            }
        }

        private static int compare(ModifierOption left, ModifierOption right) {
            int order = Integer.compare(left.sortOrder(), right.sortOrder());
            return order != 0 ? order : left.publicId().compareTo(right.publicId());
        }
    }

    public record ModifierOption(
            String publicId,
            String name,
            long priceDeltaMinor,
            boolean available,
            int sortOrder) {

        public ModifierOption {
            requirePublicId(publicId, "modifier option publicId");
            requireText(name, 120, "modifier option name");
            if (priceDeltaMinor < -MAX_MONEY_MINOR || priceDeltaMinor > MAX_MONEY_MINOR) {
                throw new IllegalArgumentException("modifier price delta is outside accepted bounds");
            }
            requireSortOrder(sortOrder, "modifier option sortOrder");
        }
    }
}
