package com.salamu.menu.menu.internal.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Strong ETag derived from every ordered field of the actual public representation. */
final class PublicMenuRepresentationTag {

    private static final Pattern SAFE_TENANT_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private PublicMenuRepresentationTag() {
    }

    static String etag(String tenantSlug, PublicMenuResponse menu) {
        if (tenantSlug == null || !SAFE_TENANT_SLUG.matcher(tenantSlug).matches()) {
            throw new IllegalArgumentException("tenantSlug must be normalized");
        }
        var canonical = new CanonicalDigest();
        canonical.value("schema", "public-menu-response-v1");
        canonical.value("restaurantId", menu.restaurantId().toString());
        canonical.value("version", Long.toString(menu.version()));
        canonical.value("publishedAt", menu.publishedAt().toString());
        canonical.value("currency", menu.currency());
        canonical.value("categoryCount", Integer.toString(menu.categories().size()));
        for (var category : menu.categories()) {
            canonical.value("categoryId", category.categoryId());
            canonical.value("categoryName", category.name());
            canonical.value("categoryDescription", category.description());
            canonical.value("categoryImageUrl", category.imageUrl() == null ? null : category.imageUrl().toString());
            canonical.value("categorySortOrder", Integer.toString(category.sortOrder()));
        }
        canonical.value("itemCount", Integer.toString(menu.items().size()));
        for (var item : menu.items()) {
            canonical.value("menuItemId", item.menuItemId());
            canonical.value("itemCategoryId", item.categoryId());
            canonical.value("itemName", item.name());
            canonical.value("itemDescription", item.description());
            canonical.value("itemPriceMinor", Long.toString(item.priceMinor()));
            canonical.value("itemImageUrl", item.imageUrl() == null ? null : item.imageUrl().toString());
            canonical.value("itemAvailable", Boolean.toString(item.available()));
            canonical.value("itemSortOrder", Integer.toString(item.sortOrder()));
        }
        return "\"menu-" + tenantSlug + "-v" + menu.version() + "-" + canonical.hex() + "\"";
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
                return;
            }
            digest.update((byte) 1);
            bytes(value.getBytes(StandardCharsets.UTF_8));
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
