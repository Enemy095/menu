package com.salamu.menu.menu.internal.application;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.TenantAsset;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantAssetService;
import com.salamu.menu.tenancy.api.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads one immutable published menu using a fixed query plan and one tenant-scoped asset batch. */
@Service
public class PublicMenuQueryService {

    private static final int MAX_ASSET_BATCH_SIZE = 500;

    private final PublicMenuReadRepository repository;
    private final TenantAssetService tenantAssetService;

    public PublicMenuQueryService(
            PublicMenuReadRepository repository,
            TenantAssetService tenantAssetService) {
        this.repository = repository;
        this.tenantAssetService = tenantAssetService;
    }

    @Transactional(readOnly = true)
    public PublishedMenu getCurrentPublished(TenantContext tenantContext) {
        Objects.requireNonNull(tenantContext, "tenantContext");
        var tenantId = tenantContext.tenantId();
        var header = repository.findCurrentPublished(tenantId)
                .orElseThrow(PublishedMenuNotFoundException::new);
        PersistedMenuValidator.validateHeader(header);
        var categories = repository.findCategories(tenantId, header.menuVersionId());
        var items = repository.findItems(tenantId, header.menuVersionId());
        PersistedMenuValidator.validate(header, categories, items);

        Set<TenantAssetId> assetIds = new LinkedHashSet<>();
        categories.stream()
                .map(PublicMenuReadRepository.MenuCategoryRow::imageAssetId)
                .filter(Objects::nonNull)
                .forEach(assetIds::add);
        items.stream()
                .map(PublicMenuReadRepository.MenuItemRow::imageAssetId)
                .filter(Objects::nonNull)
                .forEach(assetIds::add);
        if (assetIds.size() > MAX_ASSET_BATCH_SIZE) {
            throw new InvalidPublishedMenuException();
        }

        var assets = tenantAssetService.getActiveAssets(tenantId, assetIds);
        if (assets == null) {
            throw new InvalidPublishedMenuException();
        }
        var publishedCategories = categories.stream()
                .map(category -> new PublishedMenu.Category(
                        category.categoryId(),
                        category.name(),
                        category.description(),
                        imageUrl(assets, tenantContext, category.imageAssetId(), AssetKind.MENU_CATEGORY_IMAGE),
                        category.sortOrder()))
                .toList();
        var publishedItems = items.stream()
                .map(item -> new PublishedMenu.Item(
                        item.menuItemId(),
                        item.categoryId(),
                        item.name(),
                        item.description(),
                        item.priceMinor(),
                        imageUrl(assets, tenantContext, item.imageAssetId(), AssetKind.MENU_ITEM_IMAGE),
                        item.available(),
                        item.sortOrder()))
                .toList();

        return new PublishedMenu(
                header.restaurantId(),
                header.version(),
                header.publishedAt(),
                header.currency(),
                publishedCategories,
                publishedItems);
    }

    private static URI imageUrl(
            Map<TenantAssetId, TenantAsset> assets,
            TenantContext tenantContext,
            TenantAssetId assetId,
            AssetKind expectedKind) {
        if (assetId == null) {
            return null;
        }
        var asset = assets.get(assetId);
        if (asset == null
                || !tenantContext.tenantId().equals(asset.tenantId())
                || asset.kind() != expectedKind) {
            return null;
        }
        return asset.publicUrl();
    }
}
