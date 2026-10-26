package com.salamu.menu.restaurant.internal.application;

import java.util.List;

import com.salamu.menu.restaurant.api.RestaurantId;
import com.salamu.menu.restaurant.api.RestaurantStatus;

public interface DefaultRestaurantRepository {

    List<StoredRestaurant> findActiveDefaults();

    record StoredRestaurant(
            RestaurantId id,
            String externalKey,
            String displayName,
            RestaurantStatus status) {
    }
}
