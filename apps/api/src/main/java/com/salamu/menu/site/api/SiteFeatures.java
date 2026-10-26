package com.salamu.menu.site.api;

public record SiteFeatures(
        boolean orders,
        boolean contacts,
        boolean geoLocation,
        boolean socialLinks,
        boolean delivery,
        boolean pickup,
        boolean preorder,
        boolean customTheme,
        boolean whatsappFallback,
        boolean promotions,
        boolean loyalty,
        boolean reservations,
        boolean deliveryBlocks) {
}
