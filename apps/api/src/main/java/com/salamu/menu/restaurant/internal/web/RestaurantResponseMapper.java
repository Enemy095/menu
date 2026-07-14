package com.salamu.menu.restaurant.internal.web;

import com.salamu.menu.restaurant.internal.application.PublicRestaurantConfiguration;

import org.springframework.stereotype.Component;

@Component
class RestaurantResponseMapper {

    RestaurantResponse map(PublicRestaurantConfiguration source) {
        var branding = source.branding();
        var hero = branding.hero();
        var seo = source.seo();
        var profile = source.profile();
        var features = source.features();
        var limits = source.limits();
        return new RestaurantResponse(
                source.restaurantId(),
                source.slug(),
                new RestaurantResponse.Branding(
                        branding.logoUrl(),
                        branding.logoAlt(),
                        branding.faviconUrl(),
                        new RestaurantResponse.Hero(
                                hero.imageUrl(),
                                hero.imageAlt(),
                                hero.eyebrow(),
                                hero.title(),
                                hero.subtitle(),
                                hero.ctaLabel(),
                                hero.benefits().stream()
                                        .map(value -> new RestaurantResponse.HeroBenefit(value.icon(), value.text()))
                                        .toList()),
                        new RestaurantResponse.DesignTokens(
                                branding.designTokens().primaryColor(),
                                branding.designTokens().secondaryColor(),
                                branding.designTokens().accentColor(),
                                branding.designTokens().backgroundColor(),
                                branding.designTokens().surfaceColor(),
                                branding.designTokens().textColor(),
                                branding.designTokens().mutedTextColor(),
                                branding.designTokens().borderRadius(),
                                branding.designTokens().fontFamily()),
                        branding.localizedTexts().stream()
                                .map(value -> new RestaurantResponse.LocalizedText(
                                        value.locale(),
                                        value.displayName(),
                                        value.shortName(),
                                        value.businessTypeLabel(),
                                        value.cuisineText(),
                                        value.slogan(),
                                        value.introText(),
                                        value.menuSectionTitle(),
                                        value.cartTitle(),
                                        value.orderTypeLegend()))
                                .toList()),
                new RestaurantResponse.SeoMetadata(
                        seo.title(), seo.description(), seo.canonicalUrl(), seo.openGraphImageUrl()),
                new RestaurantResponse.PublicProfile(
                        profile.publicPhone(),
                        profile.publicPhoneLabel(),
                        profile.address(),
                        profile.workingHoursText(),
                        profile.location() == null
                                ? null
                                : new RestaurantResponse.GeoLocation(
                                        profile.location().latitude(),
                                        profile.location().longitude(),
                                        profile.location().label()),
                        profile.socialLinks().stream()
                                .map(value -> new RestaurantResponse.SocialLink(
                                        value.type(), value.label(), value.url()))
                                .toList()),
                new RestaurantResponse.Features(
                        features.orders(),
                        features.contacts(),
                        features.geoLocation(),
                        features.socialLinks(),
                        features.delivery(),
                        features.pickup(),
                        features.preorder(),
                        features.customTheme(),
                        features.whatsappFallback(),
                        features.promotions(),
                        features.loyalty(),
                        features.reservations(),
                        features.deliveryBlocks()),
                source.orderTypes().stream()
                        .map(value -> new RestaurantResponse.AvailableOrderType(
                                value.code(), value.label(), value.isDefault()))
                        .toList(),
                new RestaurantResponse.Limits(
                        limits.maxItemQuantity(),
                        limits.maxTotalItems(),
                        limits.maxNameLength(),
                        limits.maxPhoneLength(),
                        limits.maxAddressLength(),
                        limits.maxCommentLength(),
                        limits.minOrderMinor(),
                        limits.freeDeliveryThresholdMinor(),
                        limits.delayedOrderConfirmationMinutes()),
                source.locale(),
                source.currency(),
                source.timezone());
    }
}
