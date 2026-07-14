package com.salamu.menu.restaurant.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.salamu.menu.restaurant.api.DefaultRestaurantSpec;
import com.salamu.menu.restaurant.api.RestaurantSemanticHash;
import com.salamu.menu.tenancy.api.TenantId;

import org.junit.jupiter.api.Test;

class RestaurantProvisioningValueTests {

    @Test
    void canonicalHashIsStableAndTenantScoped() {
        var tenantA = new TenantId(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        var tenantB = new TenantId(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
        var first = new DefaultRestaurantSpec(tenantA, "tenant-a", "main", "Restaurant");
        var equal = new DefaultRestaurantSpec(tenantA, "tenant-a", "main", "Restaurant");
        var otherTenant = new DefaultRestaurantSpec(tenantB, "tenant-b", "main", "Restaurant");

        assertThat(RestaurantProvisioningHasher.hash(first))
                .isEqualTo(RestaurantProvisioningHasher.hash(equal))
                .isNotEqualTo(RestaurantProvisioningHasher.hash(otherTenant));
        assertThat(RestaurantProvisioningHasher.hash(first).schemaVersion())
                .isEqualTo(RestaurantSemanticHash.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void specRejectsNonCanonicalNaturalKeysAndDisplayNames() {
        var tenantId = new TenantId(UUID.randomUUID());

        assertThatThrownBy(() -> new DefaultRestaurantSpec(tenantId, "Tenant", "main", "Restaurant"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultRestaurantSpec(tenantId, "tenant", "Main", "Restaurant"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultRestaurantSpec(tenantId, "tenant", "main", " Restaurant"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
