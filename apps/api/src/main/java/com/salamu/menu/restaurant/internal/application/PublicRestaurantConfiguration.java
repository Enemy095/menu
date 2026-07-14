package com.salamu.menu.restaurant.internal.application;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/** Typed, public-only application read model. Persistence-only fields never enter this model. */
public record PublicRestaurantConfiguration(
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
        String timezone,
        String entityTag) {

    public record Branding(
            URI logoUrl,
            String logoAlt,
            URI faviconUrl,
            Hero hero,
            DesignTokens designTokens,
            List<LocalizedText> localizedTexts) {
    }

    public record Hero(
            URI imageUrl,
            String imageAlt,
            String eyebrow,
            String title,
            String subtitle,
            String ctaLabel,
            List<HeroBenefit> benefits) {
    }

    public record HeroBenefit(String icon, String text) {
    }

    public record DesignTokens(
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

    public record LocalizedText(
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

    public record SeoMetadata(
            String title,
            String description,
            URI canonicalUrl,
            URI openGraphImageUrl) {
    }

    public record PublicProfile(
            String publicPhone,
            String publicPhoneLabel,
            String address,
            String workingHoursText,
            GeoLocation location,
            List<SocialLink> socialLinks) {
    }

    public record GeoLocation(BigDecimal latitude, BigDecimal longitude, String label) {
    }

    public record SocialLink(String type, String label, URI url) {
    }

    public record Features(
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

    public record AvailableOrderType(String code, String label, boolean isDefault) {
    }

    public record Limits(
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
