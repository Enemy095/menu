package com.salamu.menu.asset.internal.application;

import com.salamu.menu.asset.api.AssetKind;

public final class AssetKindStorageName {

    private AssetKindStorageName() {
    }

    static String of(AssetKind kind) {
        return switch (kind) {
            case LOGO -> "logo";
            case FAVICON -> "favicon";
            case HERO -> "hero";
            case OPEN_GRAPH -> "open_graph";
            case CATEGORY -> "menu_category_image";
            case MENU_ITEM -> "menu_item_image";
        };
    }

    public static AssetKind fromStorageName(String storageName) {
        return switch (storageName) {
            case "logo" -> AssetKind.LOGO;
            case "favicon" -> AssetKind.FAVICON;
            case "hero" -> AssetKind.HERO;
            case "open_graph" -> AssetKind.OPEN_GRAPH;
            case "menu_category_image" -> AssetKind.CATEGORY;
            case "menu_item_image" -> AssetKind.MENU_ITEM;
            default -> throw new IllegalArgumentException("Unsupported asset type");
        };
    }
}
