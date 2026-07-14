package com.salamu.menu.tenancy.api;

/** Complete, versioned feature registry accepted by MIG-001 tenant configuration. */
public enum TenantFeatureKey {
    ORDERS("orders"),
    CONTACTS("contacts"),
    GEO_LOCATION("geo_location"),
    SOCIAL_LINKS("social_links"),
    DELIVERY("delivery"),
    PICKUP("pickup"),
    PREORDER("preorder"),
    CUSTOM_THEME("custom_theme"),
    WHATSAPP_FALLBACK("whatsapp_fallback"),
    PROMOTIONS("promotions"),
    LOYALTY("loyalty"),
    RESERVATIONS("reservations"),
    DELIVERY_BLOCKS("delivery_blocks");

    private final String databaseKey;

    TenantFeatureKey(String databaseKey) {
        this.databaseKey = databaseKey;
    }

    public String databaseKey() {
        return databaseKey;
    }

    public static TenantFeatureKey fromDatabaseKey(String value) {
        for (var key : values()) {
            if (key.databaseKey.equals(value)) {
                return key;
            }
        }
        throw new IllegalArgumentException("Unknown tenant feature key");
    }
}
