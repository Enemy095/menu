package com.salamu.menu.site.api;

import java.math.BigDecimal;
import java.util.Objects;

public record GeoLocation(BigDecimal latitude, BigDecimal longitude, String label) {

    public GeoLocation {
        Objects.requireNonNull(latitude, "latitude");
        Objects.requireNonNull(longitude, "longitude");
        label = SiteValues.required(label, 1, 240, "label");
        if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("location is invalid");
        }
    }
}
