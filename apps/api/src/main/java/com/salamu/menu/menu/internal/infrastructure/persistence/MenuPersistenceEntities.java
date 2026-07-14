package com.salamu.menu.menu.internal.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "MenuVersionPersistenceEntity")
@Table(name = "menu_versions")
class MenuVersionEntity {

    @Id
    UUID id;

    @Column(name = "tenant_id", nullable = false)
    UUID tenantId;

    @Column(name = "restaurant_id", nullable = false)
    UUID restaurantId;

    @Column(nullable = false)
    long version;

    @Column(nullable = false, length = 32)
    String status;

    @Column(name = "published_at")
    OffsetDateTime publishedAt;

    @Column(name = "created_at", nullable = false)
    OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;
}

@Entity(name = "MenuCategoryPersistenceEntity")
@Table(name = "menu_categories")
class MenuCategoryEntity {

    @Id
    UUID id;

    @Column(name = "tenant_id", nullable = false)
    UUID tenantId;

    @Column(name = "menu_version_id", nullable = false)
    UUID menuVersionId;

    @Column(name = "public_id", nullable = false, length = 120)
    String publicId;

    @Column(nullable = false, length = 120)
    String name;

    @Column(length = 500)
    String description;

    @Column(name = "image_asset_id")
    UUID imageAssetId;

    @Column(name = "sort_order", nullable = false)
    int sortOrder;

    @Column(nullable = false)
    boolean available;
}

@Entity(name = "MenuItemPersistenceEntity")
@Table(name = "menu_items")
class MenuItemEntity {

    @Id
    UUID id;

    @Column(name = "tenant_id", nullable = false)
    UUID tenantId;

    @Column(name = "menu_version_id", nullable = false)
    UUID menuVersionId;

    @Column(name = "category_id", nullable = false)
    UUID categoryId;

    @Column(name = "public_id", nullable = false, length = 120)
    String publicId;

    @Column(nullable = false, length = 160)
    String name;

    @Column(length = 1000)
    String description;

    @Column(name = "price_minor", nullable = false)
    long priceMinor;

    @Column(nullable = false, length = 3)
    String currency;

    @Column(name = "image_asset_id")
    UUID imageAssetId;

    @Column(nullable = false)
    boolean available;

    @Column(name = "sort_order", nullable = false)
    int sortOrder;
}
