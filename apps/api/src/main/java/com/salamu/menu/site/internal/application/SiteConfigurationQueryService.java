package com.salamu.menu.site.internal.application;

import java.math.BigDecimal;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.salamu.menu.asset.api.AssetCatalog;
import com.salamu.menu.asset.api.AssetId;
import com.salamu.menu.asset.api.AssetKind;
import com.salamu.menu.asset.api.AssetUnavailableException;
import com.salamu.menu.asset.api.PublicAsset;
import com.salamu.menu.restaurant.api.DefaultRestaurantQuery;
import com.salamu.menu.restaurant.api.DefaultRestaurantUnavailableException;
import com.salamu.menu.restaurant.api.RestaurantStatus;
import com.salamu.menu.site.api.AvailableOrderType;
import com.salamu.menu.site.api.Branding;
import com.salamu.menu.site.api.DeliveryConfiguration;
import com.salamu.menu.site.api.DeliveryFeePolicy;
import com.salamu.menu.site.api.DesignTokens;
import com.salamu.menu.site.api.GeoLocation;
import com.salamu.menu.site.api.Hero;
import com.salamu.menu.site.api.HeroBenefit;
import com.salamu.menu.site.api.LocalizedSiteText;
import com.salamu.menu.site.api.OpeningInterval;
import com.salamu.menu.site.api.OrderLimits;
import com.salamu.menu.site.api.OrderType;
import com.salamu.menu.site.api.PublicProfile;
import com.salamu.menu.site.api.Seo;
import com.salamu.menu.site.api.SiteConfigurationQuery;
import com.salamu.menu.site.api.SiteConfigurationSnapshot;
import com.salamu.menu.site.api.SiteFeatures;
import com.salamu.menu.site.api.SiteNotReadyException;
import com.salamu.menu.site.api.SocialLink;
import com.salamu.menu.site.api.SocialLinkType;
import com.salamu.menu.site.api.WeeklySchedule;
import com.salamu.menu.site.api.WeeklyScheduleDay;
import com.salamu.menu.site.internal.application.SiteConfigurationRepository.StoredFeature;
import com.salamu.menu.site.internal.application.SiteConfigurationRepository.StoredSiteConfiguration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SiteConfigurationQueryService implements SiteConfigurationQuery {

    private static final Set<String> KNOWN_FEATURES = Set.of(
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
    private static final List<String> ORDER_TYPE_KEYS = List.of("preorder", "delivery", "pickup");
    private static final Set<String> HERO_FIELDS = Set.of(
            "imageAssetId", "imageAlt", "eyebrow", "title", "subtitle", "ctaLabel", "benefits");
    private static final Set<String> DESIGN_TOKEN_FIELDS = Set.of(
            "primaryColor", "secondaryColor", "accentColor", "backgroundColor", "surfaceColor",
            "textColor", "mutedTextColor", "borderRadius", "fontFamily");
    private static final Set<String> LOCALIZED_TEXT_FIELDS = Set.of(
            "locale", "displayName", "shortName", "businessTypeLabel", "cuisineText", "slogan",
            "introText", "menuSectionTitle", "cartTitle", "orderTypeLegend");
    private static final Set<String> SEO_FIELDS = Set.of(
            "title", "description", "canonicalUrl", "openGraphAssetId");
    private static final Set<String> SOCIAL_LINK_FIELDS = Set.of("type", "label", "url");
    private static final Set<String> WORKING_HOURS_FIELDS = Set.of("day", "opensAt", "closesAt");
    private static final Set<String> ORDER_LIMIT_FIELDS = Set.of(
            "maxItemQuantity", "maxTotalItems", "maxNameLength", "maxPhoneLength",
            "maxAddressLength", "maxCommentLength", "delayedOrderConfirmationMinutes");
    private static final Set<String> NON_PUBLIC_LIMIT_FIELDS = Set.of(
            "minOrderMinor", "freeDeliveryThresholdMinor");
    private static final Pattern TIME = Pattern.compile("^(?:[01][0-9]|2[0-3]):[0-5][0-9]$");

    private final SiteConfigurationRepository repository;
    private final DefaultRestaurantQuery defaultRestaurantQuery;
    private final AssetCatalog assetCatalog;
    private final ObjectMapper objectMapper;
    private final SiteRepresentationHasher representationHasher;

    public SiteConfigurationQueryService(
            SiteConfigurationRepository repository,
            DefaultRestaurantQuery defaultRestaurantQuery,
            AssetCatalog assetCatalog,
            ObjectMapper objectMapper,
            SiteRepresentationHasher representationHasher) {
        this.repository = repository;
        this.defaultRestaurantQuery = defaultRestaurantQuery;
        this.assetCatalog = assetCatalog;
        this.objectMapper = objectMapper;
        this.representationHasher = representationHasher;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SiteConfigurationSnapshot current() {
        try {
            var stored = repository.load().orElseThrow(SiteNotReadyException::new);
            var configuration = stored.configuration();
            requireActiveSingleton(configuration);

            var restaurant = defaultRestaurantQuery.requireActiveDefault();
            if (restaurant.status() != RestaurantStatus.ACTIVE) {
                throw new SiteNotReadyException();
            }

            var designTokens = designTokens(object(configuration.designTokensJson()));
            var localizedTexts = localizedTexts(array(configuration.localizedTextsJson()));
            var hero = hero(object(configuration.heroJson()));
            var seo = seo(object(configuration.seoJson()));
            var schedule = schedule(
                    configuration.timezone(),
                    configuration.workingHoursText(),
                    array(configuration.workingHoursJson()));
            var profile = profile(configuration, array(configuration.socialLinksJson()));
            var featureConfiguration = features(stored.features());

            var assetIds = assetIds(configuration, hero, seo);
            Map<AssetId, PublicAsset> assets = assetCatalog.requireActive(assetIds);
            var logo = requireAsset(new AssetId(configuration.logoAssetId()), AssetKind.LOGO, assets);
            var favicon = optionalAsset(configuration.faviconAssetId(), AssetKind.FAVICON, assets);
            var heroAsset = optionalAsset(hero.imageAssetId(), AssetKind.HERO, assets);
            var openGraphAsset = optionalAsset(seo.openGraphAssetId(), AssetKind.OPEN_GRAPH, assets);

            var branding = new Branding(
                    logo.publicUrl(),
                    requiredText(configuration.displayName(), 1, 160),
                    publicUrl(favicon),
                    new Hero(
                            publicUrl(heroAsset),
                            hero.imageAlt(),
                            hero.eyebrow(),
                            hero.title(),
                            hero.subtitle(),
                            hero.ctaLabel(),
                            hero.benefits()),
                    designTokens,
                    localizedTexts);
            var publicSeo = new Seo(
                    seo.title(), seo.description(), seo.canonicalUrl(), publicUrl(openGraphAsset));
            var delivery = new DeliveryConfiguration(
                    featureConfiguration.features().delivery(), true, DeliveryFeePolicy.NONE);
            var data = new SiteConfigurationData(
                    restaurant.id(),
                    branding,
                    publicSeo,
                    profile,
                    featureConfiguration.features(),
                    featureConfiguration.orderTypes(),
                    featureConfiguration.limits(),
                    schedule,
                    delivery,
                    configuration.locale(),
                    configuration.currency(),
                    configuration.timezone());
            String representationHash = representationHasher.hash(data);
            return new SiteConfigurationSnapshot(
                    data.restaurantId(),
                    data.branding(),
                    data.seo(),
                    data.profile(),
                    data.features(),
                    data.orderTypes(),
                    data.limits(),
                    data.schedule(),
                    data.deliveryConfiguration(),
                    data.locale(),
                    data.currency(),
                    data.timezone(),
                    representationHash);
        } catch (SiteNotReadyException exception) {
            throw exception;
        } catch (DefaultRestaurantUnavailableException
                | AssetUnavailableException
                | SiteConfigurationCorruptException
                | IllegalArgumentException
                | NullPointerException exception) {
            throw new SiteNotReadyException();
        }
    }

    private static void requireActiveSingleton(StoredSiteConfiguration configuration) {
        if (configuration == null
                || configuration.singletonId() != 1
                || !"active".equals(configuration.status())) {
            throw new SiteNotReadyException();
        }
        requiredText(configuration.businessType(), 1, 80);
        if (configuration.logoAssetId() == null) {
            throw new SiteNotReadyException();
        }
    }

    private DesignTokens designTokens(JsonNode node) {
        requireExactFields(node, DESIGN_TOKEN_FIELDS);
        return new DesignTokens(
                text(node, "primaryColor", 1, 7),
                text(node, "secondaryColor", 1, 7),
                text(node, "accentColor", 1, 7),
                text(node, "backgroundColor", 1, 7),
                text(node, "surfaceColor", 1, 7),
                text(node, "textColor", 1, 7),
                text(node, "mutedTextColor", 1, 7),
                text(node, "borderRadius", 1, 32),
                text(node, "fontFamily", 1, 120));
    }

    private List<LocalizedSiteText> localizedTexts(JsonNode node) {
        if (node.isEmpty()) {
            throw corrupt();
        }
        var result = new ArrayList<LocalizedSiteText>();
        for (var value : node) {
            requireObject(value);
            requireExactFields(value, LOCALIZED_TEXT_FIELDS);
            result.add(new LocalizedSiteText(
                    text(value, "locale", 2, 16),
                    text(value, "displayName", 1, 120),
                    text(value, "shortName", 1, 80),
                    text(value, "businessTypeLabel", 1, 80),
                    presentText(value, "cuisineText", 160),
                    presentText(value, "slogan", 200),
                    presentText(value, "introText", 500),
                    text(value, "menuSectionTitle", 1, 120),
                    text(value, "cartTitle", 1, 120),
                    text(value, "orderTypeLegend", 1, 160)));
        }
        return List.copyOf(result);
    }

    private HeroData hero(JsonNode node) {
        requireAllowedFields(node, HERO_FIELDS);
        var benefits = new ArrayList<HeroBenefit>();
        JsonNode benefitsNode = node.get("benefits");
        if (benefitsNode != null) {
            if (!benefitsNode.isArray() || benefitsNode.size() > 6) {
                throw corrupt();
            }
            for (var benefit : benefitsNode) {
                requireObject(benefit);
                requireExactFields(benefit, Set.of("icon", "text"));
                benefits.add(new HeroBenefit(
                        text(benefit, "icon", 1, 16), text(benefit, "text", 1, 100)));
            }
        }
        return new HeroData(
                optionalAssetId(node, "imageAssetId"),
                optionalText(node, "imageAlt", 160),
                optionalText(node, "eyebrow", 100),
                text(node, "title", 1, 160),
                optionalText(node, "subtitle", 240),
                text(node, "ctaLabel", 1, 80),
                List.copyOf(benefits));
    }

    private SeoData seo(JsonNode node) {
        requireAllowedFields(node, SEO_FIELDS);
        return new SeoData(
                text(node, "title", 1, 120),
                text(node, "description", 1, 320),
                httpUri(text(node, "canonicalUrl", 1, 2048)),
                optionalAssetId(node, "openGraphAssetId"));
    }

    private PublicProfile profile(StoredSiteConfiguration configuration, JsonNode socialLinksNode) {
        String address = optionalText(configuration.address(), 240);
        BigDecimal latitude = configuration.latitude();
        BigDecimal longitude = configuration.longitude();
        GeoLocation location = null;
        if (latitude != null || longitude != null) {
            if (latitude == null || longitude == null || address == null || address.isBlank()) {
                throw corrupt();
            }
            location = new GeoLocation(latitude, longitude, address);
        }
        var socialLinks = new ArrayList<SocialLink>();
        if (socialLinksNode.size() > 20) {
            throw corrupt();
        }
        for (var value : socialLinksNode) {
            requireObject(value);
            requireExactFields(value, SOCIAL_LINK_FIELDS);
            socialLinks.add(new SocialLink(
                    SocialLinkType.fromContractValue(text(value, "type", 1, 32)),
                    text(value, "label", 1, 80),
                    httpUri(text(value, "url", 1, 2048))));
        }
        return new PublicProfile(
                requiredText(configuration.publicPhone(), 5, 32),
                requiredText(configuration.publicPhoneLabel(), 1, 80),
                optionalText(configuration.publicEmail(), 254),
                address,
                location,
                List.copyOf(socialLinks));
    }

    private WeeklySchedule schedule(String timezone, String displayText, JsonNode workingHoursNode) {
        var intervals = new EnumMap<DayOfWeek, List<OpeningInterval>>(DayOfWeek.class);
        for (var value : workingHoursNode) {
            requireObject(value);
            requireExactFields(value, WORKING_HOURS_FIELDS);
            DayOfWeek day = dayOfWeek(text(value, "day", 1, 9));
            intervals.computeIfAbsent(day, ignored -> new ArrayList<>())
                    .add(new OpeningInterval(
                            localTime(text(value, "opensAt", 5, 5)),
                            localTime(text(value, "closesAt", 5, 5))));
        }
        var days = new ArrayList<WeeklyScheduleDay>(7);
        for (var day : DayOfWeek.values()) {
            days.add(new WeeklyScheduleDay(day, intervals.getOrDefault(day, List.of())));
        }
        return new WeeklySchedule(timezone, requiredText(displayText, 1, 240), days);
    }

    private FeatureConfiguration features(List<StoredFeature> rows) {
        var known = new LinkedHashMap<String, ParsedFeature>();
        for (StoredFeature row : rows) {
            if (row == null || row.key() == null || !KNOWN_FEATURES.contains(row.key())) {
                continue;
            }
            JsonNode config = object(row.configJson());
            ParsedFeature parsed;
            if ("orders".equals(row.key())) {
                requireExactFields(config, Set.of("limits"));
                parsed = new ParsedFeature(row.enabled(), null, false, limits(requiredObject(config, "limits")));
            } else if (ORDER_TYPE_KEYS.contains(row.key())) {
                requireExactFields(config, Set.of("label", "default"));
                parsed = new ParsedFeature(
                        row.enabled(), text(config, "label", 1, 80), bool(config, "default"), null);
            } else {
                requireExactFields(config, Set.of());
                parsed = new ParsedFeature(row.enabled(), null, false, null);
            }
            if (known.putIfAbsent(row.key(), parsed) != null) {
                throw corrupt();
            }
        }
        ParsedFeature orders = known.get("orders");
        if (orders == null || orders.limits() == null) {
            throw corrupt();
        }
        var orderTypes = new ArrayList<AvailableOrderType>();
        int defaults = 0;
        for (String key : ORDER_TYPE_KEYS) {
            ParsedFeature feature = known.get(key);
            if (feature == null) {
                continue;
            }
            if (!feature.enabled()) {
                if (feature.defaultOrderType()) {
                    throw corrupt();
                }
                continue;
            }
            if (feature.defaultOrderType()) {
                defaults++;
            }
            orderTypes.add(new AvailableOrderType(orderType(key), feature.label(), feature.defaultOrderType()));
        }
        if (orderTypes.isEmpty() || defaults != 1) {
            throw corrupt();
        }
        var features = new SiteFeatures(
                enabled(known, "orders"),
                enabled(known, "contacts"),
                enabled(known, "geo_location"),
                enabled(known, "social_links"),
                enabled(known, "delivery"),
                enabled(known, "pickup"),
                enabled(known, "preorder"),
                enabled(known, "custom_theme"),
                enabled(known, "whatsapp_fallback"),
                enabled(known, "promotions"),
                enabled(known, "loyalty"),
                enabled(known, "reservations"),
                enabled(known, "delivery_blocks"));
        return new FeatureConfiguration(features, List.copyOf(orderTypes), orders.limits());
    }

    private OrderLimits limits(JsonNode node) {
        requireObject(node);
        var allowed = new LinkedHashSet<>(ORDER_LIMIT_FIELDS);
        allowed.addAll(NON_PUBLIC_LIMIT_FIELDS);
        requireAllowedFields(node, allowed);
        if (!fieldNames(node).containsAll(ORDER_LIMIT_FIELDS)) {
            throw corrupt();
        }
        for (String nonPublicField : NON_PUBLIC_LIMIT_FIELDS) {
            optionalNonNegativeLong(node, nonPublicField);
        }
        return new OrderLimits(
                integer(node, "maxItemQuantity"),
                integer(node, "maxTotalItems"),
                integer(node, "maxNameLength"),
                integer(node, "maxPhoneLength"),
                integer(node, "maxAddressLength"),
                integer(node, "maxCommentLength"),
                integer(node, "delayedOrderConfirmationMinutes"));
    }

    private static LinkedHashSet<AssetId> assetIds(
            StoredSiteConfiguration configuration,
            HeroData hero,
            SeoData seo) {
        var ids = new LinkedHashSet<AssetId>();
        ids.add(new AssetId(configuration.logoAssetId()));
        addIfPresent(ids, configuration.faviconAssetId());
        addIfPresent(ids, hero.imageAssetId());
        addIfPresent(ids, seo.openGraphAssetId());
        return ids;
    }

    private static void addIfPresent(Set<AssetId> ids, UUID value) {
        if (value != null) {
            ids.add(new AssetId(value));
        }
    }

    private static PublicAsset requireAsset(
            AssetId id,
            AssetKind expectedKind,
            Map<AssetId, PublicAsset> assets) {
        PublicAsset asset = assets == null ? null : assets.get(id);
        if (asset == null || asset.kind() != expectedKind) {
            throw corrupt();
        }
        return asset;
    }

    private static PublicAsset optionalAsset(
            UUID id,
            AssetKind expectedKind,
            Map<AssetId, PublicAsset> assets) {
        return id == null ? null : requireAsset(new AssetId(id), expectedKind, assets);
    }

    private static URI publicUrl(PublicAsset asset) {
        return asset == null ? null : asset.publicUrl();
    }

    private JsonNode object(String value) {
        JsonNode node = read(value);
        requireObject(node);
        return node;
    }

    private JsonNode array(String value) {
        JsonNode node = read(value);
        if (node == null || !node.isArray()) {
            throw corrupt();
        }
        return node;
    }

    private JsonNode read(String value) {
        if (value == null) {
            throw corrupt();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw corrupt();
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        requireObject(value);
        return value;
    }

    private static void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw corrupt();
        }
    }

    private static void requireExactFields(JsonNode node, Set<String> expected) {
        requireObject(node);
        if (!fieldNames(node).equals(expected)) {
            throw corrupt();
        }
    }

    private static void requireAllowedFields(JsonNode node, Set<String> allowed) {
        requireObject(node);
        if (!allowed.containsAll(fieldNames(node))) {
            throw corrupt();
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        var fields = new LinkedHashSet<String>();
        node.properties().forEach(property -> fields.add(property.getKey()));
        return Set.copyOf(fields);
    }

    private static String text(JsonNode node, String field, int minimum, int maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw corrupt();
        }
        return requiredText(value.asString(), minimum, maximum);
    }

    private static String presentText(JsonNode node, String field, int maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asString().length() > maximum) {
            throw corrupt();
        }
        return value.asString();
    }

    private static String optionalText(JsonNode node, String field, int maximum) {
        JsonNode value = node.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw corrupt();
        }
        return optionalText(value.asString(), maximum);
    }

    private static String requiredText(String value, int minimum, int maximum) {
        if (value == null || value.length() < minimum || value.length() > maximum || value.isBlank()) {
            throw corrupt();
        }
        return value;
    }

    private static String optionalText(String value, int maximum) {
        if (value != null && value.length() > maximum) {
            throw corrupt();
        }
        return value;
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw corrupt();
        }
        return value.booleanValue();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw corrupt();
        }
        return value.intValue();
    }

    private static void optionalNonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)) {
            throw corrupt();
        }
    }

    private static UUID optionalAssetId(JsonNode node, String field) {
        String value = optionalText(node, field, 36);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw corrupt();
        }
    }

    private static URI httpUri(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute()
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw corrupt();
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw corrupt();
        }
    }

    private static DayOfWeek dayOfWeek(String value) {
        return switch (value) {
            case "monday" -> DayOfWeek.MONDAY;
            case "tuesday" -> DayOfWeek.TUESDAY;
            case "wednesday" -> DayOfWeek.WEDNESDAY;
            case "thursday" -> DayOfWeek.THURSDAY;
            case "friday" -> DayOfWeek.FRIDAY;
            case "saturday" -> DayOfWeek.SATURDAY;
            case "sunday" -> DayOfWeek.SUNDAY;
            default -> throw corrupt();
        };
    }

    private static LocalTime localTime(String value) {
        if (!TIME.matcher(value).matches()) {
            throw corrupt();
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw corrupt();
        }
    }

    private static OrderType orderType(String key) {
        return OrderType.valueOf(key.toUpperCase(Locale.ROOT));
    }

    private static boolean enabled(Map<String, ParsedFeature> features, String key) {
        ParsedFeature feature = features.get(key);
        return feature != null && feature.enabled();
    }

    private static SiteConfigurationCorruptException corrupt() {
        return new SiteConfigurationCorruptException();
    }

    private record HeroData(
            UUID imageAssetId,
            String imageAlt,
            String eyebrow,
            String title,
            String subtitle,
            String ctaLabel,
            List<HeroBenefit> benefits) {
    }

    private record SeoData(String title, String description, URI canonicalUrl, UUID openGraphAssetId) {
    }

    private record ParsedFeature(
            boolean enabled,
            String label,
            boolean defaultOrderType,
            OrderLimits limits) {
    }

    private record FeatureConfiguration(
            SiteFeatures features,
            List<AvailableOrderType> orderTypes,
            OrderLimits limits) {
    }
}
