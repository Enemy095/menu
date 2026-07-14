package com.salamu.menu.menu.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.salamu.menu.menu.api.MenuSemanticHash;
import com.salamu.menu.menu.api.PublishedMenuSnapshot;

/** Canonical, collection-order-sensitive hash for the provisioning API contract. */
final class MenuSnapshotHasher {

    private MenuSnapshotHasher() {
    }

    static MenuSemanticHash hash(PublishedMenuSnapshot snapshot) {
        var seed = new CanonicalSeed();
        seed.add("schema", "published-menu-v1");
        seed.add("tenantId", snapshot.tenantId().value());
        seed.add("restaurantId", snapshot.restaurantId());
        seed.add("version", snapshot.version());
        seed.add("publishedAt", snapshot.publishedAt());
        seed.add("currency", snapshot.currency());
        seed.add("categories.count", snapshot.categories().size());
        for (var category : snapshot.categories()) {
            seed.add("category.id", category.categoryId());
            seed.add("category.name", category.name());
            seed.add("category.description", category.description());
            seed.add("category.imageAssetId", assetId(category.imageAsset()));
            seed.add("category.imageAssetSha256", assetSha256(category.imageAsset()));
            seed.add("category.sortOrder", category.sortOrder());
            seed.add("category.available", category.available());
        }
        seed.add("items.count", snapshot.items().size());
        for (var item : snapshot.items()) {
            seed.add("item.id", item.menuItemId());
            seed.add("item.categoryId", item.categoryId());
            seed.add("item.name", item.name());
            seed.add("item.description", item.description());
            seed.add("item.priceMinor", item.priceMinor());
            seed.add("item.currency", item.currency());
            seed.add("item.imageAssetId", assetId(item.imageAsset()));
            seed.add("item.imageAssetSha256", assetSha256(item.imageAsset()));
            seed.add("item.available", item.available());
            seed.add("item.sortOrder", item.sortOrder());
        }

        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(seed.value().getBytes(StandardCharsets.UTF_8));
            return new MenuSemanticHash(MenuSemanticHash.CURRENT_SCHEMA_VERSION, HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static String assetId(com.salamu.menu.menu.api.MenuAssetReference value) {
        return value == null ? null : value.assetId().value().toString();
    }

    private static String assetSha256(com.salamu.menu.menu.api.MenuAssetReference value) {
        return value == null ? null : value.sha256();
    }

    private static final class CanonicalSeed {

        private final StringBuilder value = new StringBuilder();

        void add(String name, Object field) {
            append(name);
            append(field == null ? null : field.toString());
        }

        private void append(String field) {
            if (field == null) {
                value.append("-1:");
            } else {
                value.append(field.length()).append(':').append(field);
            }
        }

        String value() {
            return value.toString();
        }
    }
}
