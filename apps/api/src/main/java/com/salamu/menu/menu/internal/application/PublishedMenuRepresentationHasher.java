package com.salamu.menu.menu.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import com.salamu.menu.menu.api.PublishedMenuSnapshot;
import com.salamu.menu.restaurant.api.RestaurantId;

final class PublishedMenuRepresentationHasher {

    private PublishedMenuRepresentationHasher() {
    }

    static String hash(
            RestaurantId restaurantId,
            long version,
            Instant publishedAt,
            String currency,
            List<PublishedMenuSnapshot.Category> categories,
            List<PublishedMenuSnapshot.Item> items) {
        var digest = new CanonicalDigest();
        digest.value("schema", "public-menu-response-v1");
        digest.value("restaurantId", restaurantId.value().toString());
        digest.value("version", Long.toString(version));
        digest.value("publishedAt", publishedAt.toString());
        digest.value("currency", currency);
        digest.value("categoryCount", Integer.toString(categories.size()));
        for (var category : categories) {
            digest.value("categoryId", category.publicId());
            digest.value("categoryName", category.name());
            digest.value("categoryDescription", category.description());
            digest.value("categoryImageUrl", string(category.imageUrl()));
            digest.value("categorySortOrder", Integer.toString(category.sortOrder()));
        }
        digest.value("itemCount", Integer.toString(items.size()));
        for (var item : items) {
            digest.value("menuItemId", item.publicId());
            digest.value("itemCategoryId", item.categoryPublicId());
            digest.value("itemName", item.name());
            digest.value("itemDescription", item.description());
            digest.value("itemPriceMinor", Long.toString(item.priceMinor()));
            digest.value("itemImageUrl", string(item.imageUrl()));
            digest.value("itemAvailable", Boolean.toString(item.available()));
            digest.value("itemSortOrder", Integer.toString(item.sortOrder()));
            digest.value("modifierGroupCount", Integer.toString(item.modifierGroups().size()));
            for (var group : item.modifierGroups()) {
                digest.value("modifierGroupId", group.publicId());
                digest.value("modifierGroupName", group.name());
                digest.value("modifierGroupMinSelections", Integer.toString(group.minSelections()));
                digest.value("modifierGroupMaxSelections", Integer.toString(group.maxSelections()));
                digest.value("modifierGroupSortOrder", Integer.toString(group.sortOrder()));
                digest.value("modifierOptionCount", Integer.toString(group.options().size()));
                for (var option : group.options()) {
                    digest.value("modifierOptionId", option.publicId());
                    digest.value("modifierOptionName", option.name());
                    digest.value("modifierOptionPriceDeltaMinor", Long.toString(option.priceDeltaMinor()));
                    digest.value("modifierOptionAvailable", Boolean.toString(option.available()));
                    digest.value("modifierOptionSortOrder", Integer.toString(option.sortOrder()));
                }
            }
        }
        return digest.hex();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static final class CanonicalDigest {

        private final MessageDigest digest;

        private CanonicalDigest() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        private void value(String field, String value) {
            bytes(field.getBytes(StandardCharsets.UTF_8));
            if (value == null) {
                digest.update((byte) 0);
            } else {
                digest.update((byte) 1);
                bytes(value.getBytes(StandardCharsets.UTF_8));
            }
        }

        private void bytes(byte[] value) {
            int length = value.length;
            digest.update((byte) (length >>> 24));
            digest.update((byte) (length >>> 16));
            digest.update((byte) (length >>> 8));
            digest.update((byte) length);
            digest.update(value);
        }

        private String hex() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
