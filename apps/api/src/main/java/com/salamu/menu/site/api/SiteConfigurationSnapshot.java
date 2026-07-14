package com.salamu.menu.site.api;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import com.salamu.menu.restaurant.api.RestaurantId;

public record SiteConfigurationSnapshot(
        RestaurantId restaurantId,
        Branding branding,
        Seo seo,
        PublicProfile profile,
        SiteFeatures features,
        List<AvailableOrderType> orderTypes,
        OrderLimits limits,
        WeeklySchedule schedule,
        DeliveryConfiguration deliveryConfiguration,
        String locale,
        String currency,
        String timezone,
        String representationHash) {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    public SiteConfigurationSnapshot {
        Objects.requireNonNull(restaurantId, "restaurantId");
        Objects.requireNonNull(branding, "branding");
        Objects.requireNonNull(seo, "seo");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(features, "features");
        orderTypes = SiteValues.list(orderTypes, "orderTypes");
        if (orderTypes.isEmpty()
                || orderTypes.stream().filter(AvailableOrderType::isDefault).count() != 1
                || orderTypes.stream().map(AvailableOrderType::code).distinct().count() != orderTypes.size()) {
            throw new IllegalArgumentException("orderTypes are invalid");
        }
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(deliveryConfiguration, "deliveryConfiguration");
        locale = SiteValues.locale(locale);
        currency = SiteValues.currency(currency);
        timezone = SiteValues.timezone(timezone);
        if (!timezone.equals(schedule.timezone())) {
            throw new IllegalArgumentException("schedule timezone differs from site timezone");
        }
        Objects.requireNonNull(representationHash, "representationHash");
        if (!SHA_256.matcher(representationHash).matches()) {
            throw new IllegalArgumentException("representationHash is invalid");
        }
    }
}
