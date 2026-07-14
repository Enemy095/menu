package com.salamu.menu.restaurant.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.salamu.menu.restaurant.internal.application.PublicRestaurantConfiguration.AvailableOrderType;
import com.salamu.menu.restaurant.internal.application.PublicRestaurantConfiguration.Features;
import com.salamu.menu.restaurant.internal.application.PublicRestaurantConfigurationRepository.StoredConfiguration;
import com.salamu.menu.restaurant.internal.application.PublicRestaurantConfigurationRepository.StoredFeature;
import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.TenantAsset;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantAssetNotFoundException;
import com.salamu.menu.tenancy.api.TenantAssetService;
import com.salamu.menu.tenancy.api.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicRestaurantQuery {

    private static final List<String> ORDER_TYPE_KEYS = List.of("preorder", "delivery", "pickup");
    private static final Set<String> KNOWN_KEYS = Set.of(
            "orders",
            "contacts",
            "geo_location",
            "social_links",
            "delivery",
            "pickup",
            "preorder",
            "custom_theme",
            "whatsapp_fallback",
            "promotions",
            "loyalty",
            "reservations",
            "delivery_blocks");

    private final PublicRestaurantConfigurationRepository repository;
    private final TenantAssetService assetService;

    public PublicRestaurantQuery(
            PublicRestaurantConfigurationRepository repository,
            TenantAssetService assetService) {
        this.repository = repository;
        this.assetService = assetService;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicRestaurantConfiguration get(TenantContext tenantContext) {
        try {
            var stored = repository.findActiveDefault(tenantContext.tenantId())
                    .orElseThrow(RestaurantConfigurationNotFoundException::new);
            if (!stored.slug().equals(tenantContext.slug())) {
                throw new RestaurantConfigurationCorruptException();
            }

            var featureRows = repository.findFeatures(tenantContext.tenantId());
            var featuresByKey = knownFeatures(featureRows);
            var orderTypes = orderTypes(featuresByKey);
            var orders = requiredFeature(featuresByKey, "orders");
            var limits = orders.limits();
            validateLimits(limits);

            var requestedAssetIds = assetIds(stored);
            var assets = assetService.getActiveAssets(tenantContext.tenantId(), requestedAssetIds);
            requireAllConfiguredAssets(requestedAssetIds, assets);
            var logo = requiredAsset(stored.logoAssetId(), AssetKind.LOGO, assets);
            optionalAsset(stored.faviconAssetId(), AssetKind.FAVICON, assets);
            optionalAsset(stored.heroAssetId(), AssetKind.HERO, assets);
            optionalAsset(stored.openGraphAssetId(), AssetKind.OPEN_GRAPH, assets);

            var hero = stored.hero();
            if (stored.heroAssetId() != null) {
                hero = new PublicRestaurantConfiguration.Hero(
                        assets.get(stored.heroAssetId()).publicUrl(),
                        hero.imageAlt(),
                        hero.eyebrow(),
                        hero.title(),
                        hero.subtitle(),
                        hero.ctaLabel(),
                        hero.benefits());
            }
            var seo = stored.seoWithoutImage();
            if (stored.openGraphAssetId() != null) {
                seo = new PublicRestaurantConfiguration.SeoMetadata(
                        seo.title(),
                        seo.description(),
                        seo.canonicalUrl(),
                        assets.get(stored.openGraphAssetId()).publicUrl());
            }

            var result = new PublicRestaurantConfiguration(
                    stored.restaurantId(),
                    stored.slug(),
                    new PublicRestaurantConfiguration.Branding(
                            logo.publicUrl(),
                            stored.displayName(),
                            publicUrl(stored.faviconAssetId(), assets),
                            hero,
                            stored.designTokens(),
                            List.copyOf(stored.localizedTexts())),
                    seo,
                    stored.profile(),
                    featureView(featuresByKey),
                    orderTypes,
                    limits,
                    stored.locale(),
                    stored.currency(),
                    stored.timezone(),
                    "");
            validateRequiredPublicFields(result);
            return withEntityTag(result, entityTag(result));
        } catch (TenantAssetNotFoundException | IllegalArgumentException exception) {
            throw new RestaurantConfigurationCorruptException(exception);
        }
    }

    private static Map<String, StoredFeature> knownFeatures(List<StoredFeature> rows) {
        var result = new HashMap<String, StoredFeature>();
        for (var row : rows) {
            if (!KNOWN_KEYS.contains(row.key())) {
                continue;
            }
            if (result.put(row.key(), row) != null) {
                throw new RestaurantConfigurationCorruptException();
            }
        }
        return Map.copyOf(result);
    }

    private static List<AvailableOrderType> orderTypes(Map<String, StoredFeature> features) {
        var result = new ArrayList<AvailableOrderType>();
        int defaults = 0;
        for (var key : ORDER_TYPE_KEYS) {
            var feature = features.get(key);
            if (feature == null) {
                continue;
            }
            if (!feature.enabled()) {
                if (feature.defaultOrderType()) {
                    throw new RestaurantConfigurationCorruptException();
                }
                continue;
            }
            if (feature.label() == null || feature.label().isBlank() || feature.label().length() > 80) {
                throw new RestaurantConfigurationCorruptException();
            }
            if (feature.defaultOrderType()) {
                defaults++;
            }
            result.add(new AvailableOrderType(key, feature.label(), feature.defaultOrderType()));
        }
        if (result.isEmpty() || defaults != 1) {
            throw new RestaurantConfigurationCorruptException();
        }
        return List.copyOf(result);
    }

    private static StoredFeature requiredFeature(Map<String, StoredFeature> features, String key) {
        var feature = features.get(key);
        if (feature == null || feature.limits() == null) {
            throw new RestaurantConfigurationCorruptException();
        }
        return feature;
    }

    private static void validateLimits(PublicRestaurantConfiguration.Limits limits) {
        if (limits.maxItemQuantity() < 1 || limits.maxItemQuantity() > 999
                || limits.maxTotalItems() < 1 || limits.maxTotalItems() > 9999
                || limits.maxNameLength() < 1 || limits.maxNameLength() > 500
                || limits.maxPhoneLength() < 5 || limits.maxPhoneLength() > 100
                || limits.maxAddressLength() < 1 || limits.maxAddressLength() > 2000
                || limits.maxCommentLength() < 1 || limits.maxCommentLength() > 5000
                || negative(limits.minOrderMinor())
                || negative(limits.freeDeliveryThresholdMinor())
                || limits.delayedOrderConfirmationMinutes() < 0
                || limits.delayedOrderConfirmationMinutes() > 1440) {
            throw new RestaurantConfigurationCorruptException();
        }
    }

    private static boolean negative(Long value) {
        return value != null && value < 0;
    }

    private static LinkedHashSet<TenantAssetId> assetIds(StoredConfiguration stored) {
        var result = new LinkedHashSet<TenantAssetId>();
        if (stored.logoAssetId() == null) {
            throw new RestaurantConfigurationCorruptException();
        }
        addIfPresent(result, stored.logoAssetId());
        addIfPresent(result, stored.faviconAssetId());
        addIfPresent(result, stored.heroAssetId());
        addIfPresent(result, stored.openGraphAssetId());
        return result;
    }

    private static void addIfPresent(Set<TenantAssetId> target, TenantAssetId assetId) {
        if (assetId != null) {
            target.add(assetId);
        }
    }

    private static void requireAllConfiguredAssets(
            Set<TenantAssetId> requested,
            Map<TenantAssetId, TenantAsset> resolved) {
        if (!resolved.keySet().containsAll(requested)) {
            throw new RestaurantConfigurationCorruptException();
        }
    }

    private static TenantAsset requiredAsset(
            TenantAssetId assetId,
            AssetKind expectedKind,
            Map<TenantAssetId, TenantAsset> assets) {
        var asset = assets.get(assetId);
        if (asset == null || asset.kind() != expectedKind) {
            throw new RestaurantConfigurationCorruptException();
        }
        return asset;
    }

    private static void optionalAsset(
            TenantAssetId assetId,
            AssetKind expectedKind,
            Map<TenantAssetId, TenantAsset> assets) {
        if (assetId != null) {
            requiredAsset(assetId, expectedKind, assets);
        }
    }

    private static java.net.URI publicUrl(
            TenantAssetId assetId,
            Map<TenantAssetId, TenantAsset> assets) {
        return assetId == null ? null : assets.get(assetId).publicUrl();
    }

    private static Features featureView(Map<String, StoredFeature> features) {
        return new Features(
                enabled(features, "orders"),
                enabled(features, "contacts"),
                enabled(features, "geo_location"),
                enabled(features, "social_links"),
                enabled(features, "delivery"),
                enabled(features, "pickup"),
                enabled(features, "preorder"),
                enabled(features, "custom_theme"),
                enabled(features, "whatsapp_fallback"),
                enabled(features, "promotions"),
                enabled(features, "loyalty"),
                enabled(features, "reservations"),
                enabled(features, "delivery_blocks"));
    }

    private static boolean enabled(Map<String, StoredFeature> features, String key) {
        var feature = features.get(key);
        return feature != null && feature.enabled();
    }

    private static void validateRequiredPublicFields(PublicRestaurantConfiguration result) {
        if (blank(result.slug()) || blank(result.locale()) || blank(result.currency()) || blank(result.timezone())
                || result.restaurantId() == null || result.branding() == null || result.seo() == null
                || result.profile() == null || result.branding().logoUrl() == null
                || blank(result.branding().logoAlt()) || result.branding().logoAlt().length() > 160
                || result.branding().hero() == null
                || result.branding().designTokens() == null || result.branding().localizedTexts().isEmpty()
                || blank(result.profile().publicPhone()) || blank(result.profile().publicPhoneLabel())
                || blank(result.profile().workingHoursText()) || result.profile().socialLinks() == null) {
            throw new RestaurantConfigurationCorruptException();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static PublicRestaurantConfiguration withEntityTag(
            PublicRestaurantConfiguration value,
            String entityTag) {
        return new PublicRestaurantConfiguration(
                value.restaurantId(),
                value.slug(),
                value.branding(),
                value.seo(),
                value.profile(),
                value.features(),
                value.orderTypes(),
                value.limits(),
                value.locale(),
                value.currency(),
                value.timezone(),
                entityTag);
    }

    /** Hashes the complete public representation in response order; persistence revisions are deliberately excluded. */
    private static String entityTag(PublicRestaurantConfiguration value) {
        var seed = new CanonicalSeed();
        seed.add(value.restaurantId());
        seed.add(value.slug());

        var branding = value.branding();
        seed.add(branding.logoUrl());
        seed.add(branding.logoAlt());
        seed.add(branding.faviconUrl());
        var hero = branding.hero();
        seed.add(hero.imageUrl());
        seed.add(hero.imageAlt());
        seed.add(hero.eyebrow());
        seed.add(hero.title());
        seed.add(hero.subtitle());
        seed.add(hero.ctaLabel());
        seed.add(hero.benefits().size());
        hero.benefits().forEach(benefit -> {
            seed.add(benefit.icon());
            seed.add(benefit.text());
        });

        var tokens = branding.designTokens();
        seed.add(tokens.primaryColor());
        seed.add(tokens.secondaryColor());
        seed.add(tokens.accentColor());
        seed.add(tokens.backgroundColor());
        seed.add(tokens.surfaceColor());
        seed.add(tokens.textColor());
        seed.add(tokens.mutedTextColor());
        seed.add(tokens.borderRadius());
        seed.add(tokens.fontFamily());
        seed.add(branding.localizedTexts().size());
        branding.localizedTexts().forEach(text -> {
            seed.add(text.locale());
            seed.add(text.displayName());
            seed.add(text.shortName());
            seed.add(text.businessTypeLabel());
            seed.add(text.cuisineText());
            seed.add(text.slogan());
            seed.add(text.introText());
            seed.add(text.menuSectionTitle());
            seed.add(text.cartTitle());
            seed.add(text.orderTypeLegend());
        });

        var seo = value.seo();
        seed.add(seo.title());
        seed.add(seo.description());
        seed.add(seo.canonicalUrl());
        seed.add(seo.openGraphImageUrl());

        var profile = value.profile();
        seed.add(profile.publicPhone());
        seed.add(profile.publicPhoneLabel());
        seed.add(profile.address());
        seed.add(profile.workingHoursText());
        if (profile.location() == null) {
            seed.add(null);
        } else {
            seed.add("location");
            seed.add(profile.location().latitude());
            seed.add(profile.location().longitude());
            seed.add(profile.location().label());
        }
        seed.add(profile.socialLinks().size());
        profile.socialLinks().forEach(link -> {
            seed.add(link.type());
            seed.add(link.label());
            seed.add(link.url());
        });

        var features = value.features();
        seed.add(features.orders());
        seed.add(features.contacts());
        seed.add(features.geoLocation());
        seed.add(features.socialLinks());
        seed.add(features.delivery());
        seed.add(features.pickup());
        seed.add(features.preorder());
        seed.add(features.customTheme());
        seed.add(features.whatsappFallback());
        seed.add(features.promotions());
        seed.add(features.loyalty());
        seed.add(features.reservations());
        seed.add(features.deliveryBlocks());

        seed.add(value.orderTypes().size());
        value.orderTypes().forEach(orderType -> {
            seed.add(orderType.code());
            seed.add(orderType.label());
            seed.add(orderType.isDefault());
        });

        var limits = value.limits();
        seed.add(limits.maxItemQuantity());
        seed.add(limits.maxTotalItems());
        seed.add(limits.maxNameLength());
        seed.add(limits.maxPhoneLength());
        seed.add(limits.maxAddressLength());
        seed.add(limits.maxCommentLength());
        seed.add(limits.minOrderMinor());
        seed.add(limits.freeDeliveryThresholdMinor());
        seed.add(limits.delayedOrderConfirmationMinutes());
        seed.add(value.locale());
        seed.add(value.currency());
        seed.add(value.timezone());

        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(seed.value().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static final class CanonicalSeed {

        private final StringBuilder value = new StringBuilder();

        void add(Object field) {
            if (field == null) {
                value.append("-1:");
                return;
            }
            var rendered = field.toString();
            value.append(rendered.length()).append(':').append(rendered);
        }

        String value() {
            return value.toString();
        }
    }
}
