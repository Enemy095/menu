package com.salamu.menu.menu.internal.application;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.salamu.menu.asset.api.AssetCatalog;
import com.salamu.menu.asset.api.AssetId;
import com.salamu.menu.asset.api.AssetKind;
import com.salamu.menu.asset.api.PublicAsset;
import com.salamu.menu.menu.api.MenuItemId;
import com.salamu.menu.menu.api.PublishedMenuNotFoundException;
import com.salamu.menu.menu.api.PublishedMenuQuery;
import com.salamu.menu.menu.api.PublishedMenuSnapshot;
import com.salamu.menu.restaurant.api.DefaultRestaurantQuery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublishedMenuQueryService implements PublishedMenuQuery {

    private static final int MAX_ASSETS = 500;

    private final DefaultRestaurantQuery restaurantQuery;
    private final PublishedMenuReadRepository repository;
    private final AssetCatalog assetCatalog;

    public PublishedMenuQueryService(
            DefaultRestaurantQuery restaurantQuery,
            PublishedMenuReadRepository repository,
            AssetCatalog assetCatalog) {
        this.restaurantQuery = Objects.requireNonNull(restaurantQuery, "restaurantQuery");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.assetCatalog = Objects.requireNonNull(assetCatalog, "assetCatalog");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublishedMenuSnapshot current() {
        var restaurant = restaurantQuery.requireActiveDefault();
        try {
            var headers = copy(repository.findPublished(restaurant.id()));
            if (headers.isEmpty()) {
                throw new PublishedMenuNotFoundException();
            }
            if (headers.size() != 1 || headers.getFirst() == null) {
                throw invalid();
            }
            var header = headers.getFirst();
            if (!restaurant.id().equals(header.restaurantId())) {
                throw invalid();
            }

            var categories = bounded(
                    repository.findCategories(header.id()),
                    PublishedMenuSnapshot.MAX_CATEGORIES);
            var items = bounded(repository.findItems(header.id()), PublishedMenuSnapshot.MAX_ITEMS);
            var groups = bounded(
                    repository.findModifierGroups(header.id()),
                    PublishedMenuSnapshot.MAX_ITEMS * PublishedMenuSnapshot.MAX_MODIFIER_GROUPS_PER_ITEM);
            var options = bounded(
                    repository.findModifierOptions(header.id()),
                    PublishedMenuSnapshot.MAX_ITEMS
                            * PublishedMenuSnapshot.MAX_MODIFIER_GROUPS_PER_ITEM
                            * PublishedMenuSnapshot.MAX_OPTIONS_PER_GROUP);

            var assets = resolveAssets(categories, items);
            var categoryProjection = projectCategories(categories, assets);
            var itemProjection = projectItems(header.currency(), categories, items, groups, options, assets);
            var hash = PublishedMenuRepresentationHasher.hash(
                    restaurant.id(),
                    header.version(),
                    header.publishedAt(),
                    header.currency(),
                    categoryProjection,
                    itemProjection);
            return new PublishedMenuSnapshot(
                    restaurant.id(),
                    header.id(),
                    header.version(),
                    header.publishedAt(),
                    header.currency(),
                    categoryProjection,
                    itemProjection,
                    hash);
        } catch (PublishedMenuNotFoundException | InvalidPublishedMenuException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidPublishedMenuException(exception);
        }
    }

    private Map<AssetId, PublicAsset> resolveAssets(
            List<PublishedMenuReadRepository.CategoryRow> categories,
            List<PublishedMenuReadRepository.ItemRow> items) {
        Set<AssetId> ids = new LinkedHashSet<>();
        categories.stream()
                .map(PublishedMenuReadRepository.CategoryRow::imageAssetId)
                .filter(Objects::nonNull)
                .forEach(ids::add);
        items.stream()
                .map(PublishedMenuReadRepository.ItemRow::imageAssetId)
                .filter(Objects::nonNull)
                .forEach(ids::add);
        if (ids.size() > MAX_ASSETS) {
            throw invalid();
        }
        Map<AssetId, PublicAsset> resolved = assetCatalog.requireActive(ids);
        if (resolved == null || resolved.size() != ids.size() || !resolved.keySet().equals(ids)) {
            throw invalid();
        }
        return resolved;
    }

    private static List<PublishedMenuSnapshot.Category> projectCategories(
            List<PublishedMenuReadRepository.CategoryRow> rows,
            Map<AssetId, PublicAsset> assets) {
        Set<UUID> ids = new LinkedHashSet<>();
        List<PublishedMenuSnapshot.Category> result = new ArrayList<>(rows.size());
        for (var row : rows) {
            if (row == null || row.id() == null || !ids.add(row.id())) {
                throw invalid();
            }
            result.add(new PublishedMenuSnapshot.Category(
                    row.publicId(),
                    row.name(),
                    row.description(),
                    imageUrl(row.imageAssetId(), AssetKind.CATEGORY, assets),
                    row.sortOrder()));
        }
        return List.copyOf(result);
    }

    private static List<PublishedMenuSnapshot.Item> projectItems(
            String versionCurrency,
            List<PublishedMenuReadRepository.CategoryRow> categoryRows,
            List<PublishedMenuReadRepository.ItemRow> itemRows,
            List<PublishedMenuReadRepository.ModifierGroupRow> groupRows,
            List<PublishedMenuReadRepository.ModifierOptionRow> optionRows,
            Map<AssetId, PublicAsset> assets) {
        Map<UUID, String> categoryPublicIds = new LinkedHashMap<>();
        for (var category : categoryRows) {
            if (categoryPublicIds.putIfAbsent(category.id(), category.publicId()) != null) {
                throw invalid();
            }
        }

        Map<MenuItemId, List<PublishedMenuReadRepository.ModifierGroupRow>> groupsByItem = new LinkedHashMap<>();
        Set<MenuItemId> itemIds = new LinkedHashSet<>();
        for (var item : itemRows) {
            if (item == null || item.id() == null || !itemIds.add(item.id())) {
                throw invalid();
            }
            groupsByItem.put(item.id(), new ArrayList<>());
        }

        Map<UUID, List<PublishedMenuReadRepository.ModifierOptionRow>> optionsByGroup = new LinkedHashMap<>();
        for (var group : groupRows) {
            if (group == null
                    || group.id() == null
                    || group.menuItemId() == null
                    || optionsByGroup.putIfAbsent(group.id(), new ArrayList<>()) != null) {
                throw invalid();
            }
            var itemGroups = groupsByItem.get(group.menuItemId());
            if (itemGroups == null || itemGroups.size() >= PublishedMenuSnapshot.MAX_MODIFIER_GROUPS_PER_ITEM) {
                throw invalid();
            }
            itemGroups.add(group);
        }
        for (var option : optionRows) {
            if (option == null || option.id() == null || option.modifierGroupId() == null) {
                throw invalid();
            }
            var groupOptions = optionsByGroup.get(option.modifierGroupId());
            if (groupOptions == null || groupOptions.size() >= PublishedMenuSnapshot.MAX_OPTIONS_PER_GROUP) {
                throw invalid();
            }
            groupOptions.add(option);
        }

        List<PublishedMenuSnapshot.Item> result = new ArrayList<>(itemRows.size());
        for (var row : itemRows) {
            var categoryPublicId = categoryPublicIds.get(row.categoryId());
            if (categoryPublicId == null
                    || !categoryPublicId.equals(row.categoryPublicId())
                    || !versionCurrency.equals(row.currency())) {
                throw invalid();
            }
            var modifierGroups = new ArrayList<PublishedMenuSnapshot.ModifierGroup>();
            for (var group : groupsByItem.get(row.id())) {
                var storedOptions = optionsByGroup.get(group.id());
                if (storedOptions == null || storedOptions.isEmpty()) {
                    throw invalid();
                }
                var modifierOptions = storedOptions.stream()
                        .map(option -> new PublishedMenuSnapshot.ModifierOption(
                                option.publicId(),
                                option.name(),
                                option.priceDeltaMinor(),
                                option.available(),
                                option.sortOrder()))
                        .toList();
                modifierGroups.add(new PublishedMenuSnapshot.ModifierGroup(
                        group.publicId(),
                        group.name(),
                        group.minSelections(),
                        group.maxSelections(),
                        group.sortOrder(),
                        modifierOptions));
            }
            result.add(new PublishedMenuSnapshot.Item(
                    row.id(),
                    row.publicId(),
                    categoryPublicId,
                    row.name(),
                    row.description(),
                    row.priceMinor(),
                    row.currency(),
                    imageUrl(row.imageAssetId(), AssetKind.MENU_ITEM, assets),
                    row.available(),
                    row.sortOrder(),
                    modifierGroups));
        }
        return List.copyOf(result);
    }

    private static URI imageUrl(AssetId id, AssetKind expectedKind, Map<AssetId, PublicAsset> assets) {
        if (id == null) {
            return null;
        }
        var asset = assets.get(id);
        if (asset == null || !id.equals(asset.id()) || asset.kind() != expectedKind) {
            throw invalid();
        }
        return asset.publicUrl();
    }

    private static <T> List<T> copy(List<T> rows) {
        if (rows == null) {
            throw invalid();
        }
        return List.copyOf(rows);
    }

    private static <T> List<T> bounded(List<T> rows, int maximum) {
        var copy = copy(rows);
        if (copy.size() > maximum) {
            throw invalid();
        }
        return copy;
    }

    private static InvalidPublishedMenuException invalid() {
        return new InvalidPublishedMenuException();
    }
}
