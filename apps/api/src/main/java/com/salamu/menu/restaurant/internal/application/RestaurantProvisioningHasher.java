package com.salamu.menu.restaurant.internal.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.salamu.menu.restaurant.api.DefaultRestaurantSpec;
import com.salamu.menu.restaurant.api.RestaurantSemanticHash;
import com.salamu.menu.restaurant.internal.application.RestaurantProvisioningRepository.StoredRestaurant;

final class RestaurantProvisioningHasher {

    private RestaurantProvisioningHasher() {
    }

    static RestaurantSemanticHash hash(DefaultRestaurantSpec spec) {
        return hash(spec.tenantId().value().toString(), spec.restaurantSlug(), spec.displayName(), "active", "true");
    }

    static RestaurantSemanticHash hash(StoredRestaurant restaurant) {
        return hash(
                restaurant.tenantId().value().toString(),
                restaurant.restaurantSlug(),
                restaurant.displayName(),
                restaurant.status().databaseValue(),
                Boolean.toString(restaurant.defaultRestaurant()));
    }

    private static RestaurantSemanticHash hash(String... fields) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "restaurant-default:v1");
            for (var field : fields) {
                add(digest, field);
            }
            return new RestaurantSemanticHash(
                    RestaurantSemanticHash.CURRENT_SCHEMA_VERSION,
                    java.util.HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void add(MessageDigest digest, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
