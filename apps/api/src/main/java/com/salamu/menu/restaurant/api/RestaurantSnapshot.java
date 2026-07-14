package com.salamu.menu.restaurant.api;

import java.util.Objects;
import java.util.regex.Pattern;

public record RestaurantSnapshot(
        RestaurantId id,
        String externalKey,
        String displayName,
        RestaurantStatus status) {

    private static final Pattern EXTERNAL_KEY = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    public RestaurantSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(externalKey, "externalKey");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(status, "status");
        if (externalKey.length() > 63 || !EXTERNAL_KEY.matcher(externalKey).matches()) {
            throw new IllegalArgumentException("externalKey is invalid");
        }
        if (displayName.isBlank() || displayName.length() > 160) {
            throw new IllegalArgumentException("displayName is invalid");
        }
    }
}
