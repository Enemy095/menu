package com.salamu.menu.restaurant.internal.infrastructure.persistence;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.salamu.menu.restaurant.internal.application.PublicRestaurantConfiguration;
import com.salamu.menu.restaurant.internal.application.PublicRestaurantConfigurationRepository;
import com.salamu.menu.restaurant.internal.application.RestaurantConfigurationCorruptException;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantId;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPublicRestaurantConfigurationRepository implements PublicRestaurantConfigurationRepository {

    private static final String BASE_QUERY = """
            SELECT r.id AS restaurant_id,
                   t.slug AS tenant_slug,
                   t.locale,
                   t.currency,
                   t.timezone,
                   b.display_name,
                   b.logo_asset_id,
                   b.favicon_asset_id,
                   b.design_tokens::text AS design_tokens,
                   b.localized_texts::text AS localized_texts,
                   b.hero::text AS hero,
                   b.seo_metadata::text AS seo_metadata,
                   p.public_phone,
                   p.public_phone_label,
                   p.address_text,
                   p.working_hours_text,
                   p.latitude,
                   p.longitude,
                   p.social_links::text AS social_links,
                   t.updated_at AS tenant_updated_at,
                   r.updated_at AS restaurant_updated_at,
                   b.updated_at AS branding_updated_at,
                   p.updated_at AS profile_updated_at
              FROM tenants t
              JOIN restaurants r
                ON r.tenant_id = t.id
               AND r.is_default = true
               AND r.status = 'active'
              LEFT JOIN tenant_branding b ON b.tenant_id = t.id
              LEFT JOIN tenant_profiles p ON p.tenant_id = t.id
             WHERE t.id = :tenantId
               AND t.status = 'active'
            """;

    private static final String FEATURES_QUERY = """
            SELECT feature_key, enabled, config::text AS config, updated_at
              FROM tenant_features
             WHERE tenant_id = :tenantId
             ORDER BY feature_key
            """;

    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Pattern RADIUS = Pattern.compile("^[0-9]+(?:\\.[0-9]+)?(?:px|rem)$");
    private static final Pattern LOCALE = Pattern.compile("^[a-z]{2,3}(?:-[A-Z]{2})?$");
    private static final Set<String> IANA_TIMEZONES = Set.copyOf(ZoneId.getAvailableZoneIds());
    private static final Set<String> SOCIAL_TYPES = Set.of(
            "website", "instagram", "telegram", "vk", "facebook", "other");
    private static final Set<String> ORDER_TYPE_KEYS = Set.of("preorder", "delivery", "pickup");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    JdbcPublicRestaurantConfigurationRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<StoredConfiguration> findActiveDefault(TenantId tenantId) {
        return jdbcClient.sql(BASE_QUERY)
                .param("tenantId", tenantId.value())
                .query(this::mapConfiguration)
                .optional();
    }

    @Override
    public List<StoredFeature> findFeatures(TenantId tenantId) {
        return jdbcClient.sql(FEATURES_QUERY)
                .param("tenantId", tenantId.value())
                .query(this::mapFeature)
                .list();
    }

    private StoredConfiguration mapConfiguration(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            var designTokens = designTokens(readJson(resultSet, "design_tokens"));
            var localizedTexts = localizedTexts(readJson(resultSet, "localized_texts"));
            var heroJson = readJson(resultSet, "hero");
            var seoJson = readJson(resultSet, "seo_metadata");
            var profile = profile(resultSet, readJson(resultSet, "social_links"));
            return new StoredConfiguration(
                    resultSet.getObject("restaurant_id", UUID.class),
                    requiredText(resultSet.getString("tenant_slug"), 2, 63),
                    requiredPattern(resultSet.getString("locale"), LOCALE, 16),
                    requiredPattern(resultSet.getString("currency"), Pattern.compile("^[A-Z]{3}$"), 3),
                    ianaTimezone(resultSet.getString("timezone")),
                    requiredText(resultSet.getString("display_name"), 1, 160),
                    assetId(resultSet, "logo_asset_id"),
                    assetId(resultSet, "favicon_asset_id"),
                    optionalAssetId(heroJson, "imageAssetId"),
                    optionalAssetId(seoJson, "openGraphAssetId"),
                    hero(heroJson),
                    designTokens,
                    localizedTexts,
                    seo(seoJson),
                    profile,
                    resultSet.getObject("tenant_updated_at", OffsetDateTime.class),
                    resultSet.getObject("restaurant_updated_at", OffsetDateTime.class),
                    resultSet.getObject("branding_updated_at", OffsetDateTime.class),
                    resultSet.getObject("profile_updated_at", OffsetDateTime.class));
        } catch (IllegalArgumentException exception) {
            throw new RestaurantConfigurationCorruptException(exception);
        }
    }

    private StoredFeature mapFeature(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            var key = requiredText(resultSet.getString("feature_key"), 1, 80);
            var config = readJson(resultSet, "config");
            String label = null;
            boolean defaultOrderType = false;
            PublicRestaurantConfiguration.Limits limits = null;
            if (ORDER_TYPE_KEYS.contains(key)) {
                label = optionalText(config, "label", 80);
                defaultOrderType = optionalBoolean(config, "default", false);
            } else if ("orders".equals(key)) {
                limits = limits(requiredObject(config, "limits"));
            }
            return new StoredFeature(
                    key,
                    resultSet.getBoolean("enabled"),
                    label,
                    defaultOrderType,
                    limits,
                    resultSet.getObject("updated_at", OffsetDateTime.class));
        } catch (IllegalArgumentException exception) {
            throw new RestaurantConfigurationCorruptException(exception);
        }
    }

    private JsonNode readJson(ResultSet resultSet, String column) throws SQLException {
        try {
            var node = objectMapper.readTree(resultSet.getString(column));
            if (node == null || !(node.isObject() || node.isArray())) {
                throw invalid();
            }
            return node;
        } catch (JacksonException exception) {
            throw new RestaurantConfigurationCorruptException(exception);
        }
    }

    private static PublicRestaurantConfiguration.DesignTokens designTokens(JsonNode node) {
        requireObject(node);
        return new PublicRestaurantConfiguration.DesignTokens(
                requiredPattern(text(node, "primaryColor", 1, 7), COLOR, 7),
                requiredPattern(text(node, "secondaryColor", 1, 7), COLOR, 7),
                requiredPattern(text(node, "accentColor", 1, 7), COLOR, 7),
                requiredPattern(text(node, "backgroundColor", 1, 7), COLOR, 7),
                requiredPattern(text(node, "surfaceColor", 1, 7), COLOR, 7),
                requiredPattern(text(node, "textColor", 1, 7), COLOR, 7),
                requiredPattern(text(node, "mutedTextColor", 1, 7), COLOR, 7),
                requiredPattern(text(node, "borderRadius", 1, 32), RADIUS, 32),
                text(node, "fontFamily", 1, 120));
    }

    private static List<PublicRestaurantConfiguration.LocalizedText> localizedTexts(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw invalid();
        }
        var result = new ArrayList<PublicRestaurantConfiguration.LocalizedText>();
        for (var value : node) {
            requireObject(value);
            result.add(new PublicRestaurantConfiguration.LocalizedText(
                    requiredPattern(text(value, "locale", 2, 16), LOCALE, 16),
                    text(value, "displayName", 1, 120),
                    text(value, "shortName", 1, 80),
                    text(value, "businessTypeLabel", 1, 80),
                    text(value, "cuisineText", 0, 160),
                    text(value, "slogan", 0, 200),
                    text(value, "introText", 0, 500),
                    text(value, "menuSectionTitle", 1, 120),
                    text(value, "cartTitle", 1, 120),
                    text(value, "orderTypeLegend", 1, 160)));
        }
        return List.copyOf(result);
    }

    private static PublicRestaurantConfiguration.Hero hero(JsonNode node) {
        requireObject(node);
        var benefits = new ArrayList<PublicRestaurantConfiguration.HeroBenefit>();
        var benefitsNode = node.get("benefits");
        if (benefitsNode != null) {
            if (!benefitsNode.isArray() || benefitsNode.size() > 6) {
                throw invalid();
            }
            for (var benefit : benefitsNode) {
                benefits.add(new PublicRestaurantConfiguration.HeroBenefit(
                        text(benefit, "icon", 1, 16),
                        text(benefit, "text", 1, 100)));
            }
        }
        return new PublicRestaurantConfiguration.Hero(
                null,
                optionalText(node, "imageAlt", 160),
                optionalText(node, "eyebrow", 100),
                text(node, "title", 1, 160),
                optionalText(node, "subtitle", 240),
                text(node, "ctaLabel", 1, 80),
                List.copyOf(benefits));
    }

    private static PublicRestaurantConfiguration.SeoMetadata seo(JsonNode node) {
        requireObject(node);
        return new PublicRestaurantConfiguration.SeoMetadata(
                text(node, "title", 1, 120),
                text(node, "description", 1, 320),
                httpUri(text(node, "canonicalUrl", 1, 2048)),
                null);
    }

    private static PublicRestaurantConfiguration.PublicProfile profile(
            ResultSet resultSet,
            JsonNode socialLinksNode) throws SQLException {
        var address = optionalText(resultSet.getString("address_text"), 240);
        var latitude = resultSet.getBigDecimal("latitude");
        var longitude = resultSet.getBigDecimal("longitude");
        PublicRestaurantConfiguration.GeoLocation location = null;
        if (latitude != null || longitude != null) {
            if (latitude == null || longitude == null || address == null || address.isEmpty()) {
                throw invalid();
            }
            if (latitude.compareTo(java.math.BigDecimal.valueOf(-90)) < 0
                    || latitude.compareTo(java.math.BigDecimal.valueOf(90)) > 0
                    || longitude.compareTo(java.math.BigDecimal.valueOf(-180)) < 0
                    || longitude.compareTo(java.math.BigDecimal.valueOf(180)) > 0) {
                throw invalid();
            }
            location = new PublicRestaurantConfiguration.GeoLocation(latitude, longitude, address);
        }
        if (!socialLinksNode.isArray() || socialLinksNode.size() > 20) {
            throw invalid();
        }
        var socialLinks = new ArrayList<PublicRestaurantConfiguration.SocialLink>();
        for (var link : socialLinksNode) {
            var type = text(link, "type", 1, 32);
            if (!SOCIAL_TYPES.contains(type)) {
                throw invalid();
            }
            socialLinks.add(new PublicRestaurantConfiguration.SocialLink(
                    type,
                    text(link, "label", 1, 80),
                    httpUri(text(link, "url", 1, 2048))));
        }
        return new PublicRestaurantConfiguration.PublicProfile(
                requiredText(resultSet.getString("public_phone"), 5, 32),
                requiredText(resultSet.getString("public_phone_label"), 1, 80),
                address,
                requiredText(resultSet.getString("working_hours_text"), 1, 240),
                location,
                List.copyOf(socialLinks));
    }

    private static PublicRestaurantConfiguration.Limits limits(JsonNode node) {
        return new PublicRestaurantConfiguration.Limits(
                requiredInt(node, "maxItemQuantity"),
                requiredInt(node, "maxTotalItems"),
                requiredInt(node, "maxNameLength"),
                requiredInt(node, "maxPhoneLength"),
                requiredInt(node, "maxAddressLength"),
                requiredInt(node, "maxCommentLength"),
                optionalLong(node, "minOrderMinor"),
                optionalLong(node, "freeDeliveryThresholdMinor"),
                requiredInt(node, "delayedOrderConfirmationMinutes"));
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        var value = parent.get(field);
        requireObject(value);
        return value;
    }

    private static void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
    }

    private static String text(JsonNode node, String field, int minimum, int maximum) {
        var value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        return requiredText(value.textValue(), minimum, maximum);
    }

    private static String optionalText(JsonNode node, String field, int maximum) {
        var value = node.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid();
        }
        return optionalText(value.textValue(), maximum);
    }

    private static String optionalText(String value, int maximum) {
        if (value == null) {
            return null;
        }
        if (value.length() > maximum) {
            throw invalid();
        }
        return value;
    }

    private static String requiredText(String value, int minimum, int maximum) {
        if (value == null || value.length() < minimum || value.length() > maximum) {
            throw invalid();
        }
        return value;
    }

    private static String requiredPattern(String value, Pattern pattern, int maximum) {
        var checked = requiredText(value, 1, maximum);
        if (!pattern.matcher(checked).matches()) {
            throw invalid();
        }
        return checked;
    }

    private static int requiredInt(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid();
        }
        return value.intValue();
    }

    private static Long optionalLong(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid();
        }
        return value.longValue();
    }

    private static String ianaTimezone(String value) {
        var timezone = requiredText(value, 1, 64);
        if (!IANA_TIMEZONES.contains(timezone)) {
            throw invalid();
        }
        return timezone;
    }

    private static boolean optionalBoolean(JsonNode node, String field, boolean defaultValue) {
        var value = node.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw invalid();
        }
        return value.booleanValue();
    }

    private static TenantAssetId optionalAssetId(JsonNode node, String field) {
        var value = optionalText(node, field, 36);
        if (value == null) {
            return null;
        }
        try {
            return new TenantAssetId(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static TenantAssetId assetId(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column, UUID.class);
        return value == null ? null : new TenantAssetId(value);
    }

    private static URI httpUri(String value) {
        try {
            var uri = new URI(value);
            if (!uri.isAbsolute()
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw invalid();
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid stored restaurant configuration");
    }
}
