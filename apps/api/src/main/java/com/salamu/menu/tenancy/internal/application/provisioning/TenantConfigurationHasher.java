package com.salamu.menu.tenancy.internal.application.provisioning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot;
import com.salamu.menu.tenancy.api.TenantSemanticHash;

final class TenantConfigurationHasher {

    private TenantConfigurationHasher() {
    }

    static TenantSemanticHash hash(TenantConfigurationSnapshot value) {
        var seed = new CanonicalSeed();
        seed.add("schema", "tenant-configuration-v1");
        var identity = value.identity();
        seed.add("slug", identity.slug());
        seed.add("locale", identity.locale());
        seed.add("currency", identity.currency());
        seed.add("timezone", identity.timezone());

        var branding = value.branding();
        seed.add("branding.displayName", branding.displayName());
        seed.add("branding.businessType", branding.businessType());
        seed.add("branding.logoAssetId", assetId(branding.logoAssetId()));
        seed.add("branding.faviconAssetId", assetId(branding.faviconAssetId()));
        var tokens = branding.designTokens();
        seed.add("tokens.primaryColor", tokens.primaryColor());
        seed.add("tokens.secondaryColor", tokens.secondaryColor());
        seed.add("tokens.accentColor", tokens.accentColor());
        seed.add("tokens.backgroundColor", tokens.backgroundColor());
        seed.add("tokens.surfaceColor", tokens.surfaceColor());
        seed.add("tokens.textColor", tokens.textColor());
        seed.add("tokens.mutedTextColor", tokens.mutedTextColor());
        seed.add("tokens.borderRadius", tokens.borderRadius());
        seed.add("tokens.fontFamily", tokens.fontFamily());
        seed.add("localizedTexts.count", branding.localizedTexts().size());
        branding.localizedTexts().stream()
                .sorted(Comparator.comparing(TenantConfigurationSnapshot.LocalizedText::locale))
                .forEach(text -> {
                    seed.add("localized.locale", text.locale());
                    seed.add("localized.displayName", text.displayName());
                    seed.add("localized.shortName", text.shortName());
                    seed.add("localized.businessTypeLabel", text.businessTypeLabel());
                    seed.add("localized.cuisineText", text.cuisineText());
                    seed.add("localized.slogan", text.slogan());
                    seed.add("localized.introText", text.introText());
                    seed.add("localized.menuSectionTitle", text.menuSectionTitle());
                    seed.add("localized.cartTitle", text.cartTitle());
                    seed.add("localized.orderTypeLegend", text.orderTypeLegend());
                });
        var hero = branding.hero();
        seed.add("hero.imageAssetId", assetId(hero.imageAssetId()));
        seed.add("hero.imageAlt", hero.imageAlt());
        seed.add("hero.eyebrow", hero.eyebrow());
        seed.add("hero.title", hero.title());
        seed.add("hero.subtitle", hero.subtitle());
        seed.add("hero.ctaLabel", hero.ctaLabel());
        seed.add("hero.benefits.count", hero.benefits().size());
        hero.benefits().forEach(benefit -> {
            seed.add("hero.benefit.icon", benefit.icon());
            seed.add("hero.benefit.text", benefit.text());
        });
        var seo = branding.seoMetadata();
        seed.add("seo.title", seo.title());
        seed.add("seo.description", seo.description());
        seed.add("seo.canonicalUrl", seo.canonicalUrl());
        seed.add("seo.openGraphAssetId", assetId(seo.openGraphAssetId()));

        var profile = value.profile();
        seed.add("profile.publicPhone", profile.publicPhone());
        seed.add("profile.publicPhoneLabel", profile.publicPhoneLabel());
        seed.add("profile.publicEmail", profile.publicEmail());
        seed.add("profile.address", profile.address());
        seed.add("profile.workingHours.count", profile.workingHours().size());
        profile.workingHours().forEach(hours -> {
            seed.add("workingHours.day", hours.day());
            seed.add("workingHours.opensAt", hours.opensAt());
            seed.add("workingHours.closesAt", hours.closesAt());
        });
        seed.add("profile.workingHoursText", profile.workingHoursText());
        seed.add("profile.latitude", decimal(profile.latitude()));
        seed.add("profile.longitude", decimal(profile.longitude()));
        seed.add("profile.socialLinks.count", profile.socialLinks().size());
        profile.socialLinks().forEach(link -> {
            seed.add("social.type", link.type());
            seed.add("social.label", link.label());
            seed.add("social.url", link.url());
        });

        value.features().stream()
                .sorted(Comparator.comparing(feature -> feature.key().databaseKey()))
                .forEach(feature -> {
                    seed.add("feature.key", feature.key().databaseKey());
                    seed.add("feature.enabled", feature.enabled());
                    seed.add("feature.label", feature.label());
                    seed.add("feature.default", feature.defaultOrderType());
                    var limits = feature.limits();
                    if (limits == null) {
                        seed.add("feature.limits", null);
                    } else {
                        seed.add("limits.maxItemQuantity", limits.maxItemQuantity());
                        seed.add("limits.maxTotalItems", limits.maxTotalItems());
                        seed.add("limits.maxNameLength", limits.maxNameLength());
                        seed.add("limits.maxPhoneLength", limits.maxPhoneLength());
                        seed.add("limits.maxAddressLength", limits.maxAddressLength());
                        seed.add("limits.maxCommentLength", limits.maxCommentLength());
                        seed.add("limits.minOrderMinor", limits.minOrderMinor());
                        seed.add("limits.freeDeliveryThresholdMinor", limits.freeDeliveryThresholdMinor());
                        seed.add("limits.delayedOrderConfirmationMinutes", limits.delayedOrderConfirmationMinutes());
                    }
                });

        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(seed.value().getBytes(StandardCharsets.UTF_8));
            return new TenantSemanticHash(
                    TenantSemanticHash.CURRENT_SCHEMA_VERSION,
                    HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static String assetId(com.salamu.menu.tenancy.api.TenantAssetId value) {
        return value == null ? null : value.value().toString();
    }

    private static String decimal(java.math.BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static final class CanonicalSeed {

        private final StringBuilder value = new StringBuilder();

        void add(String name, Object field) {
            append(name);
            append(field == null ? null : field.toString());
        }

        private void append(String field) {
            if (field == null) {
                value.append("-1:");
            } else {
                value.append(field.length()).append(':').append(field);
            }
        }

        String value() {
            return value.toString();
        }
    }
}
