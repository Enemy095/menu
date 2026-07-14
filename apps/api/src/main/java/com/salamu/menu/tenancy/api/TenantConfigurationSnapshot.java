package com.salamu.menu.tenancy.api;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Complete tenant-owned configuration written by the staged provisioning workflow.
 *
 * <p>The snapshot intentionally contains domain values instead of JSON blobs or persistence entities. Every
 * collection is defensively copied, and exactly the versioned thirteen-feature registry is required.</p>
 */
public record TenantConfigurationSnapshot(
        TenantId tenantId,
        SuspendedTenantSpec identity,
        Branding branding,
        Profile profile,
        List<Feature> features) {

    private static final Set<TenantFeatureKey> EXPECTED_FEATURES =
            Set.copyOf(EnumSet.allOf(TenantFeatureKey.class));

    public TenantConfigurationSnapshot {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(branding, "branding");
        Objects.requireNonNull(profile, "profile");
        features = List.copyOf(Objects.requireNonNull(features, "features"));
        var keys = new HashSet<TenantFeatureKey>();
        for (var feature : features) {
            Objects.requireNonNull(feature, "feature");
            if (!keys.add(feature.key())) {
                throw invalid("features contain a duplicate key");
            }
        }
        if (features.size() != EXPECTED_FEATURES.size() || !keys.equals(EXPECTED_FEATURES)) {
            throw invalid("configuration must contain exactly the thirteen known feature rows");
        }
        if (branding.localizedTexts().stream().noneMatch(text -> text.locale().equals(identity.locale()))) {
            throw invalid("localizedTexts must contain the tenant locale");
        }
        var enabledOrderTypes = features.stream()
                .filter(feature -> Set.of(
                        TenantFeatureKey.PREORDER,
                        TenantFeatureKey.DELIVERY,
                        TenantFeatureKey.PICKUP).contains(feature.key()))
                .filter(Feature::enabled)
                .toList();
        if (enabledOrderTypes.isEmpty()
                || enabledOrderTypes.stream().filter(Feature::defaultOrderType).count() != 1) {
            throw invalid("exactly one enabled order type must be the default");
        }
    }

    public record Branding(
            String displayName,
            String businessType,
            TenantAssetId logoAssetId,
            TenantAssetId faviconAssetId,
            DesignTokens designTokens,
            List<LocalizedText> localizedTexts,
            Hero hero,
            SeoMetadata seoMetadata) {

        public Branding {
            displayName = required(displayName, 1, 160, "displayName");
            businessType = required(businessType, 1, 80, "businessType");
            Objects.requireNonNull(logoAssetId, "logoAssetId");
            Objects.requireNonNull(designTokens, "designTokens");
            localizedTexts = List.copyOf(Objects.requireNonNull(localizedTexts, "localizedTexts"));
            if (localizedTexts.isEmpty()) {
                throw invalid("localizedTexts must not be empty");
            }
            var locales = new HashSet<String>();
            for (var localizedText : localizedTexts) {
                Objects.requireNonNull(localizedText, "localizedText");
                if (!locales.add(localizedText.locale())) {
                    throw invalid("localizedTexts contain a duplicate locale");
                }
            }
            Objects.requireNonNull(hero, "hero");
            Objects.requireNonNull(seoMetadata, "seoMetadata");
        }
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

        private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
        private static final Pattern RADIUS = Pattern.compile("^[0-9]+(?:\\.[0-9]+)?(?:px|rem)$");

        public DesignTokens {
            primaryColor = patterned(primaryColor, COLOR, 7, "primaryColor");
            secondaryColor = patterned(secondaryColor, COLOR, 7, "secondaryColor");
            accentColor = patterned(accentColor, COLOR, 7, "accentColor");
            backgroundColor = patterned(backgroundColor, COLOR, 7, "backgroundColor");
            surfaceColor = patterned(surfaceColor, COLOR, 7, "surfaceColor");
            textColor = patterned(textColor, COLOR, 7, "textColor");
            mutedTextColor = patterned(mutedTextColor, COLOR, 7, "mutedTextColor");
            borderRadius = patterned(borderRadius, RADIUS, 32, "borderRadius");
            fontFamily = required(fontFamily, 1, 120, "fontFamily");
        }
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

        private static final Pattern LOCALE = Pattern.compile("^[a-z]{2,3}(?:-[A-Z]{2})?$");

        public LocalizedText {
            locale = patterned(locale, LOCALE, 16, "locale");
            displayName = required(displayName, 1, 120, "localized displayName");
            shortName = required(shortName, 1, 80, "shortName");
            businessTypeLabel = required(businessTypeLabel, 1, 80, "businessTypeLabel");
            cuisineText = required(cuisineText, 0, 160, "cuisineText");
            slogan = required(slogan, 0, 200, "slogan");
            introText = required(introText, 0, 500, "introText");
            menuSectionTitle = required(menuSectionTitle, 1, 120, "menuSectionTitle");
            cartTitle = required(cartTitle, 1, 120, "cartTitle");
            orderTypeLegend = required(orderTypeLegend, 1, 160, "orderTypeLegend");
        }
    }

    public record Hero(
            TenantAssetId imageAssetId,
            String imageAlt,
            String eyebrow,
            String title,
            String subtitle,
            String ctaLabel,
            List<HeroBenefit> benefits) {

        public Hero {
            imageAlt = optional(imageAlt, 160, "hero imageAlt");
            eyebrow = optional(eyebrow, 100, "hero eyebrow");
            title = required(title, 1, 160, "hero title");
            subtitle = optional(subtitle, 240, "hero subtitle");
            ctaLabel = required(ctaLabel, 1, 80, "hero ctaLabel");
            benefits = List.copyOf(Objects.requireNonNull(benefits, "benefits"));
            if (benefits.size() > 6 || benefits.stream().anyMatch(Objects::isNull)) {
                throw invalid("hero benefits are invalid");
            }
        }
    }

    public record HeroBenefit(String icon, String text) {
        public HeroBenefit {
            icon = required(icon, 1, 16, "benefit icon");
            text = required(text, 1, 100, "benefit text");
        }
    }

    public record SeoMetadata(
            String title,
            String description,
            String canonicalUrl,
            TenantAssetId openGraphAssetId) {

        public SeoMetadata {
            title = required(title, 1, 120, "SEO title");
            description = required(description, 1, 320, "SEO description");
            canonicalUrl = httpUrl(canonicalUrl, "canonicalUrl");
        }
    }

    public record Profile(
            String publicPhone,
            String publicPhoneLabel,
            String publicEmail,
            String address,
            List<WorkingHoursEntry> workingHours,
            String workingHoursText,
            BigDecimal latitude,
            BigDecimal longitude,
            List<SocialLink> socialLinks) {

        public Profile {
            publicPhone = required(publicPhone, 5, 32, "publicPhone");
            publicPhoneLabel = required(publicPhoneLabel, 1, 80, "publicPhoneLabel");
            publicEmail = optional(publicEmail, 254, "publicEmail");
            address = optional(address, 240, "address");
            workingHours = List.copyOf(Objects.requireNonNull(workingHours, "workingHours"));
            workingHoursText = required(workingHoursText, 1, 240, "workingHoursText");
            if (workingHours.stream().anyMatch(Objects::isNull)) {
                throw invalid("workingHours contain null");
            }
            if ((latitude == null) != (longitude == null)) {
                throw invalid("latitude and longitude must be present together");
            }
            if (latitude != null) {
                latitude = coordinate(latitude, -90, 90, "latitude");
                longitude = coordinate(longitude, -180, 180, "longitude");
            }
            socialLinks = List.copyOf(Objects.requireNonNull(socialLinks, "socialLinks"));
            if (socialLinks.size() > 20 || socialLinks.stream().anyMatch(Objects::isNull)) {
                throw invalid("socialLinks are invalid");
            }
        }
    }

    public record WorkingHoursEntry(String day, String opensAt, String closesAt) {
        public WorkingHoursEntry {
            day = required(day, 1, 32, "working hours day");
            opensAt = required(opensAt, 1, 16, "working hours opensAt");
            closesAt = required(closesAt, 1, 16, "working hours closesAt");
        }
    }

    public record SocialLink(String type, String label, String url) {
        private static final Set<String> TYPES = Set.of(
                "website", "instagram", "telegram", "vk", "facebook", "other");

        public SocialLink {
            type = required(type, 1, 32, "social link type");
            if (!TYPES.contains(type)) {
                throw invalid("social link type is unsupported");
            }
            label = required(label, 1, 80, "social link label");
            url = httpUrl(url, "social link URL");
        }
    }

    public record Feature(
            TenantFeatureKey key,
            boolean enabled,
            String label,
            boolean defaultOrderType,
            OrderLimits limits) {

        private static final Set<TenantFeatureKey> ORDER_TYPES = Set.of(
                TenantFeatureKey.PREORDER,
                TenantFeatureKey.DELIVERY,
                TenantFeatureKey.PICKUP);

        public Feature {
            Objects.requireNonNull(key, "key");
            if (key == TenantFeatureKey.ORDERS) {
                if (label != null || defaultOrderType || limits == null) {
                    throw invalid("orders feature requires only limits");
                }
            } else if (ORDER_TYPES.contains(key)) {
                label = required(label, 1, 80, "order type label");
                if (limits != null || (!enabled && defaultOrderType)) {
                    throw invalid("order type feature configuration is invalid");
                }
            } else if (label != null || defaultOrderType || limits != null) {
                throw invalid("boolean feature must have empty configuration");
            }
        }
    }

    public record OrderLimits(
            int maxItemQuantity,
            int maxTotalItems,
            int maxNameLength,
            int maxPhoneLength,
            int maxAddressLength,
            int maxCommentLength,
            Long minOrderMinor,
            Long freeDeliveryThresholdMinor,
            int delayedOrderConfirmationMinutes) {

        public OrderLimits {
            range(maxItemQuantity, 1, 999, "maxItemQuantity");
            range(maxTotalItems, 1, 9999, "maxTotalItems");
            range(maxNameLength, 1, 500, "maxNameLength");
            range(maxPhoneLength, 5, 100, "maxPhoneLength");
            range(maxAddressLength, 1, 2000, "maxAddressLength");
            range(maxCommentLength, 1, 5000, "maxCommentLength");
            nonNegative(minOrderMinor, "minOrderMinor");
            nonNegative(freeDeliveryThresholdMinor, "freeDeliveryThresholdMinor");
            range(delayedOrderConfirmationMinutes, 0, 1440, "delayedOrderConfirmationMinutes");
        }
    }

    private static BigDecimal coordinate(BigDecimal value, int minimum, int maximum, String field) {
        Objects.requireNonNull(value, field);
        if (value.compareTo(BigDecimal.valueOf(minimum)) < 0
                || value.compareTo(BigDecimal.valueOf(maximum)) > 0) {
            throw invalid(field + " is outside the valid range");
        }
        return value.stripTrailingZeros();
    }

    private static void range(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw invalid(field + " is outside the valid range");
        }
    }

    private static void nonNegative(Long value, String field) {
        if (value != null && value < 0) {
            throw invalid(field + " must not be negative");
        }
    }

    private static String httpUrl(String value, String field) {
        var checked = required(value, 1, 2048, field);
        try {
            var uri = new URI(checked);
            if (!uri.isAbsolute()
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw invalid(field + " must be an absolute HTTP URL");
            }
            return uri.toASCIIString();
        } catch (URISyntaxException exception) {
            throw invalid(field + " must be an absolute HTTP URL");
        }
    }

    private static String patterned(String value, Pattern pattern, int maximum, String field) {
        var checked = required(value, 1, maximum, field);
        if (!pattern.matcher(checked).matches()) {
            throw invalid(field + " has an invalid canonical value");
        }
        return checked;
    }

    private static String required(String value, int minimum, int maximum, String field) {
        Objects.requireNonNull(value, field);
        if (value.length() < minimum || value.length() > maximum) {
            throw invalid(field + " has an invalid length");
        }
        return value;
    }

    private static String optional(String value, int maximum, String field) {
        if (value == null) {
            return null;
        }
        if (value.length() > maximum) {
            throw invalid(field + " has an invalid length");
        }
        return value;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
