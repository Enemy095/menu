package com.salamu.menu.site.internal.application;

import java.util.List;

import com.salamu.menu.restaurant.api.RestaurantId;
import com.salamu.menu.site.api.AvailableOrderType;
import com.salamu.menu.site.api.Branding;
import com.salamu.menu.site.api.DeliveryConfiguration;
import com.salamu.menu.site.api.OrderLimits;
import com.salamu.menu.site.api.PublicProfile;
import com.salamu.menu.site.api.Seo;
import com.salamu.menu.site.api.SiteFeatures;
import com.salamu.menu.site.api.WeeklySchedule;

record SiteConfigurationData(
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
        String timezone) {
}
