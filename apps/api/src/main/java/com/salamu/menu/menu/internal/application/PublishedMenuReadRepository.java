package com.salamu.menu.menu.internal.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.salamu.menu.asset.api.AssetId;
import com.salamu.menu.menu.api.MenuItemId;
import com.salamu.menu.menu.api.MenuVersionId;
import com.salamu.menu.restaurant.api.RestaurantId;

/** Fixed-query, bounded read port for the current single-client published menu. */
public interface PublishedMenuReadRepository {

    List<MenuHeader> findPublished(RestaurantId restaurantId);

    List<CategoryRow> findCategories(MenuVersionId menuVersionId);

    List<ItemRow> findItems(MenuVersionId menuVersionId);

    List<ModifierGroupRow> findModifierGroups(MenuVersionId menuVersionId);

    List<ModifierOptionRow> findModifierOptions(MenuVersionId menuVersionId);

    record MenuHeader(
            MenuVersionId id,
            RestaurantId restaurantId,
            long version,
            Instant publishedAt,
            String currency) {
    }

    record CategoryRow(
            UUID id,
            String publicId,
            String name,
            String description,
            AssetId imageAssetId,
            int sortOrder) {
    }

    record ItemRow(
            MenuItemId id,
            UUID categoryId,
            String categoryPublicId,
            String publicId,
            String name,
            String description,
            long priceMinor,
            String currency,
            AssetId imageAssetId,
            boolean available,
            int sortOrder) {
    }

    record ModifierGroupRow(
            UUID id,
            MenuItemId menuItemId,
            String publicId,
            String name,
            int minSelections,
            int maxSelections,
            int sortOrder) {
    }

    record ModifierOptionRow(
            UUID id,
            UUID modifierGroupId,
            String publicId,
            String name,
            long priceDeltaMinor,
            boolean available,
            int sortOrder) {
    }
}
