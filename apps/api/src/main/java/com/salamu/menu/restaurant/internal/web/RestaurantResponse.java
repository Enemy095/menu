package com.salamu.menu.restaurant.internal.web;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
record RestaurantResponse(
        UUID restaurantId,
        String slug,
        Branding branding,
        SeoMetadata seo,
        PublicProfile profile,
        Features features,
        List<AvailableOrderType> orderTypes,
        Limits limits,
        String locale,
        String currency,
        String timezone) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Branding(
            URI logoUrl,
            String logoAlt,
            URI faviconUrl,
            Hero hero,
            DesignTokens designTokens,
            List<LocalizedText> localizedTexts) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Hero(
            URI imageUrl,
            String imageAlt,
            String eyebrow,
            String title,
            String subtitle,
            String ctaLabel,
            List<HeroBenefit> benefits) {
    }

    record HeroBenefit(String icon, String text) {
    }

    record DesignTokens(
            String primaryColor,
            String secondaryColor,
            String accentColor,
            String backgroundColor,
            String surfaceColor,
            String textColor,
            String mutedTextColor,
            String borderRadius,
            String fontFamily) {
    }

    record LocalizedText(
            String locale,
            String displayName,
            String shortName,
            String businessTypeLabel,
            String cuisineText,
            String slogan,
            String introText,
            String menuSectionTitle,
            String cartTitle,
            String orderTypeLegend) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SeoMetadata(String title, String description, URI canonicalUrl, URI openGraphImageUrl) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PublicProfile(
            String publicPhone,
            String publicPhoneLabel,
            String address,
            String workingHoursText,
            GeoLocation location,
            List<SocialLink> socialLinks) {
    }

    record GeoLocation(BigDecimal latitude, BigDecimal longitude, String label) {
    }

    record SocialLink(String type, String label, URI url) {
    }

    record Features(
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

    record AvailableOrderType(String code, String label, boolean isDefault) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Limits(
            int maxItemQuantity,
            int maxTotalItems,
            int maxNameLength,
            int maxPhoneLength,
            int maxAddressLength,
            int maxCommentLength,
            Long minOrderMinor,
            Long freeDeliveryThresholdMinor,
            int delayedOrderConfirmationMinutes) {
    }
}
