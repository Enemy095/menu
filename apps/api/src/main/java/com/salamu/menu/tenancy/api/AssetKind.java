package com.salamu.menu.tenancy.api;

/** Stable tenant asset categories used in persistence and object keys. */
public enum AssetKind {
    LOGO("logo"),
    FAVICON("favicon"),
    HERO("hero"),
    OPEN_GRAPH("open_graph"),
    MENU_CATEGORY_IMAGE("menu_category_image"),
    MENU_ITEM_IMAGE("menu_item_image");

    private final String storageName;

    AssetKind(String storageName) {
        this.storageName = storageName;
    }

    public String storageName() {
        return storageName;
    }
}
