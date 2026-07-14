package com.salamu.menu.restaurant.internal.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "RestaurantPersistenceEntity")
@Table(name = "restaurants")
class RestaurantEntity {

    @Id
    UUID id;

    @Column(name = "tenant_id", nullable = false)
    UUID tenantId;

    @Column(nullable = false, length = 63)
    String slug;

    @Column(name = "display_name", nullable = false, length = 160)
    String displayName;

    @Column(nullable = false, length = 32)
    String status;

    @Column(name = "is_default", nullable = false)
    boolean defaultRestaurant;

    @Column(name = "created_at", nullable = false)
    OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;
}
