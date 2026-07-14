package com.salamu.menu.tenancy.internal.infrastructure.persistence.provisioning;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.salamu.menu.tenancy.api.SuspendedTenantSpec;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot.Branding;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot.DesignTokens;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot.Feature;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot.Hero;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot.HeroBenefit;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot.LocalizedText;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot.OrderLimits;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot.Profile;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot.SeoMetadata;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot.SocialLink;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot.WorkingHoursEntry;
import com.salamu.menu.tenancy.api.TenantFeatureKey;
import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.api.TenantLifecycle;
import com.salamu.menu.tenancy.api.TenantProvisioningException;
import com.salamu.menu.tenancy.api.TenantProvisioningException.Code;
import com.salamu.menu.tenancy.api.TenantProvisioningState.ConfigurationPresence;
import com.salamu.menu.tenancy.internal.application.provisioning.TenantProvisioningRepository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@org.springframework.context.annotation.Profile("legacy-import")
class JdbcTenantProvisioningRepository implements TenantProvisioningRepository {

    private static final String SELECT_TENANT = """
            SELECT id, slug, status, locale, currency, timezone
              FROM tenants
             WHERE %s
            """;

    private static final Set<String> DESIGN_TOKEN_FIELDS = Set.of(
            "primaryColor", "secondaryColor", "accentColor", "backgroundColor", "surfaceColor",
            "textColor", "mutedTextColor", "borderRadius", "fontFamily");
    private static final Set<String> LOCALIZED_TEXT_FIELDS = Set.of(
            "locale", "displayName", "shortName", "businessTypeLabel", "cuisineText", "slogan",
            "introText", "menuSectionTitle", "cartTitle", "orderTypeLegend");
    private static final Set<String> HERO_FIELDS = Set.of(
            "imageAssetId", "imageAlt", "eyebrow", "title", "subtitle", "ctaLabel", "benefits");
    private static final Set<String> BENEFIT_FIELDS = Set.of("icon", "text");
    private static final Set<String> SEO_FIELDS = Set.of(
            "title", "description", "canonicalUrl", "openGraphAssetId");
    private static final Set<String> WORKING_HOURS_FIELDS = Set.of("day", "opensAt", "closesAt");
    private static final Set<String> SOCIAL_LINK_FIELDS = Set.of("type", "label", "url");
    private static final Set<TenantFeatureKey> ORDER_TYPE_KEYS = Set.of(
            TenantFeatureKey.PREORDER,
            TenantFeatureKey.DELIVERY,
            TenantFeatureKey.PICKUP);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    JdbcTenantProvisioningRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<StoredTenant> findBySlug(String slug) {
        return jdbcClient.sql(SELECT_TENANT.formatted("slug = :slug"))
                .param("slug", slug)
                .query(this::tenant)
                .optional();
    }

    @Override
    public Optional<StoredTenant> findById(TenantId tenantId) {
        return jdbcClient.sql(SELECT_TENANT.formatted("id = :tenantId"))
                .param("tenantId", tenantId.value())
                .query(this::tenant)
                .optional();
    }

    @Override
    public Optional<StoredTenant> findBySlugForUpdate(String slug) {
        return jdbcClient.sql(SELECT_TENANT.formatted("slug = :slug") + " FOR UPDATE")
                .param("slug", slug)
                .query(this::tenant)
                .optional();
    }

    @Override
    public Optional<StoredTenant> findByIdForUpdate(TenantId tenantId) {
        return jdbcClient.sql(SELECT_TENANT.formatted("id = :tenantId") + " FOR UPDATE")
                .param("tenantId", tenantId.value())
                .query(this::tenant)
                .optional();
    }

    @Override
    public void insertSuspended(TenantId tenantId, SuspendedTenantSpec spec, OffsetDateTime now) {
        var updated = jdbcClient.sql("""
                INSERT INTO tenants (id, slug, status, locale, currency, timezone, created_at, updated_at)
                VALUES (:tenantId, :slug, 'suspended', :locale, :currency, :timezone, :now, :now)
                """)
                .param("tenantId", tenantId.value())
                .param("slug", spec.slug())
                .param("locale", spec.locale())
                .param("currency", spec.currency())
                .param("timezone", spec.timezone())
                .param("now", now)
                .update();
        requireOne(updated);
    }

    @Override
    public StoredConfiguration readConfiguration(StoredTenant tenant) {
        var branding = jdbcClient.sql("""
                SELECT display_name, business_type, logo_asset_id, favicon_asset_id,
                       design_tokens::text AS design_tokens,
                       localized_texts::text AS localized_texts,
                       hero::text AS hero,
                       seo_metadata::text AS seo_metadata
                  FROM tenant_branding
                 WHERE tenant_id = :tenantId
                """)
                .param("tenantId", tenant.tenantId().value())
                .query(this::branding)
                .optional();
        var profile = jdbcClient.sql("""
                SELECT public_phone, public_phone_label, public_email, address_text,
                       working_hours::text AS working_hours, working_hours_text,
                       latitude, longitude, social_links::text AS social_links
                  FROM tenant_profiles
                 WHERE tenant_id = :tenantId
                """)
                .param("tenantId", tenant.tenantId().value())
                .query(this::profile)
                .optional();
        var features = jdbcClient.sql("""
                SELECT feature_key, enabled, config::text AS config
                  FROM tenant_features
                 WHERE tenant_id = :tenantId
                 ORDER BY feature_key
                """)
                .param("tenantId", tenant.tenantId().value())
                .query(this::feature)
                .list();

        if (branding.isEmpty() && profile.isEmpty() && features.isEmpty()) {
            return new StoredConfiguration(ConfigurationPresence.ABSENT, null);
        }
        if (branding.isEmpty()
                || profile.isEmpty()
                || features.size() != TenantFeatureKey.values().length
                || features.stream().map(Feature::key).distinct().count() != TenantFeatureKey.values().length) {
            return new StoredConfiguration(ConfigurationPresence.PARTIAL, null);
        }
        try {
            return new StoredConfiguration(
                    ConfigurationPresence.COMPLETE,
                    new TenantConfigurationSnapshot(
                            tenant.tenantId(),
                            tenant.identity(),
                            branding.orElseThrow(),
                            profile.orElseThrow(),
                            features));
        } catch (IllegalArgumentException exception) {
            throw invalidPersistedState();
        }
    }

    @Override
    public void insertConfiguration(TenantConfigurationSnapshot snapshot, OffsetDateTime now) {
        var branding = snapshot.branding();
        requireOne(jdbcClient.sql("""
                INSERT INTO tenant_branding (
                    tenant_id, display_name, business_type, logo_asset_id, favicon_asset_id,
                    design_tokens, localized_texts, hero, seo_metadata, updated_at
                ) VALUES (
                    :tenantId, :displayName, :businessType, :logoAssetId, :faviconAssetId,
                    CAST(:designTokens AS jsonb), CAST(:localizedTexts AS jsonb),
                    CAST(:hero AS jsonb), CAST(:seoMetadata AS jsonb), :now
                )
                """)
                .param("tenantId", snapshot.tenantId().value())
                .param("displayName", branding.displayName())
                .param("businessType", branding.businessType())
                .param("logoAssetId", uuid(branding.logoAssetId()))
                .param("faviconAssetId", uuid(branding.faviconAssetId()))
                .param("designTokens", json(designTokensMap(branding.designTokens())))
                .param("localizedTexts", json(branding.localizedTexts().stream().map(this::localizedTextMap).toList()))
                .param("hero", json(heroMap(branding.hero())))
                .param("seoMetadata", json(seoMap(branding.seoMetadata())))
                .param("now", now)
                .update());

        var profile = snapshot.profile();
        requireOne(jdbcClient.sql("""
                INSERT INTO tenant_profiles (
                    tenant_id, public_phone, public_phone_label, public_email, address_text,
                    working_hours, working_hours_text, latitude, longitude, social_links, updated_at
                ) VALUES (
                    :tenantId, :publicPhone, :publicPhoneLabel, :publicEmail, :address,
                    CAST(:workingHours AS jsonb), :workingHoursText, :latitude, :longitude,
                    CAST(:socialLinks AS jsonb), :now
                )
                """)
                .param("tenantId", snapshot.tenantId().value())
                .param("publicPhone", profile.publicPhone())
                .param("publicPhoneLabel", profile.publicPhoneLabel())
                .param("publicEmail", profile.publicEmail())
                .param("address", profile.address())
                .param("workingHours", json(profile.workingHours().stream().map(this::workingHoursMap).toList()))
                .param("workingHoursText", profile.workingHoursText())
                .param("latitude", profile.latitude())
                .param("longitude", profile.longitude())
                .param("socialLinks", json(profile.socialLinks().stream().map(this::socialLinkMap).toList()))
                .param("now", now)
                .update());

        snapshot.features().stream()
                .sorted(Comparator.comparing(feature -> feature.key().databaseKey()))
                .forEach(feature -> requireOne(jdbcClient.sql("""
                        INSERT INTO tenant_features (tenant_id, feature_key, enabled, config, updated_at)
                        VALUES (:tenantId, :featureKey, :enabled, CAST(:config AS jsonb), :now)
                        """)
                        .param("tenantId", snapshot.tenantId().value())
                        .param("featureKey", feature.key().databaseKey())
                        .param("enabled", feature.enabled())
                        .param("config", json(featureConfig(feature)))
                        .param("now", now)
                        .update()));
    }

    @Override
    public boolean configurationAssetsMatch(TenantConfigurationSnapshot snapshot) {
        var expected = new HashMap<UUID, String>();
        var branding = snapshot.branding();
        if (!putExpectedAsset(expected, branding.logoAssetId(), "logo")
                || !putExpectedAsset(expected, branding.faviconAssetId(), "favicon")
                || !putExpectedAsset(expected, branding.hero().imageAssetId(), "hero")
                || !putExpectedAsset(expected, branding.seoMetadata().openGraphAssetId(), "open_graph")) {
            return false;
        }
        var rows = jdbcClient.sql("""
                SELECT id, asset_type
                  FROM tenant_assets
                 WHERE tenant_id = :tenantId
                   AND id IN (:assetIds)
                   AND status = 'active'
                 ORDER BY id
                """)
                .param("tenantId", snapshot.tenantId().value())
                .param("assetIds", expected.keySet())
                .query((resultSet, rowNumber) -> Map.entry(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("asset_type")))
                .list();
        return rows.size() == expected.size()
                && rows.stream().allMatch(row -> row.getValue().equals(expected.get(row.getKey())));
    }

    @Override
    public void activate(TenantId tenantId, OffsetDateTime now) {
        requireOne(jdbcClient.sql("""
                UPDATE tenants
                   SET status = 'active', updated_at = :now
                 WHERE id = :tenantId
                   AND status = 'suspended'
                """)
                .param("tenantId", tenantId.value())
                .param("now", now)
                .update());
    }

    @Override
    public void deleteConfiguration(TenantId tenantId) {
        jdbcClient.sql("DELETE FROM tenant_features WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId.value())
                .update();
        jdbcClient.sql("DELETE FROM tenant_profiles WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId.value())
                .update();
        jdbcClient.sql("DELETE FROM tenant_branding WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId.value())
                .update();
    }

    @Override
    public boolean hasTenancyOwnedReferences(TenantId tenantId) {
        return Boolean.TRUE.equals(jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM tenant_domains WHERE tenant_id = :tenantId
                    UNION ALL
                    SELECT 1 FROM tenant_assets WHERE tenant_id = :tenantId
                )
                """)
                .param("tenantId", tenantId.value())
                .query(Boolean.class)
                .single());
    }

    @Override
    public boolean deleteSuspendedTenant(TenantId tenantId) {
        return jdbcClient.sql("DELETE FROM tenants WHERE id = :tenantId AND status = 'suspended'")
                .param("tenantId", tenantId.value())
                .update() == 1;
    }

    private StoredTenant tenant(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            return new StoredTenant(
                    new TenantId(resultSet.getObject("id", UUID.class)),
                    new SuspendedTenantSpec(
                            resultSet.getString("slug"),
                            resultSet.getString("locale"),
                            resultSet.getString("currency"),
                            resultSet.getString("timezone")),
                    TenantLifecycle.fromDatabaseValue(resultSet.getString("status")));
        } catch (IllegalArgumentException exception) {
            throw invalidPersistedState();
        }
    }

    private Branding branding(ResultSet resultSet, int rowNumber) throws SQLException {
        var designTokens = object(resultSet.getString("design_tokens"), DESIGN_TOKEN_FIELDS);
        var localizedTexts = array(resultSet.getString("localized_texts"));
        var hero = object(resultSet.getString("hero"), HERO_FIELDS);
        var seo = object(resultSet.getString("seo_metadata"), SEO_FIELDS);
        return new Branding(
                resultSet.getString("display_name"),
                resultSet.getString("business_type"),
                assetId(resultSet, "logo_asset_id"),
                assetId(resultSet, "favicon_asset_id"),
                designTokens(designTokens),
                localizedTexts(localizedTexts),
                hero(hero),
                seo(seo));
    }

    private Profile profile(ResultSet resultSet, int rowNumber) throws SQLException {
        var workingHours = array(resultSet.getString("working_hours"));
        var socialLinks = array(resultSet.getString("social_links"));
        return new Profile(
                resultSet.getString("public_phone"),
                resultSet.getString("public_phone_label"),
                resultSet.getString("public_email"),
                resultSet.getString("address_text"),
                workingHours(workingHours),
                resultSet.getString("working_hours_text"),
                resultSet.getBigDecimal("latitude"),
                resultSet.getBigDecimal("longitude"),
                socialLinks(socialLinks));
    }

    private Feature feature(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            var key = TenantFeatureKey.fromDatabaseKey(resultSet.getString("feature_key"));
            var config = object(resultSet.getString("config"), featureFields(key));
            if (key == TenantFeatureKey.ORDERS) {
                requireExactFields(config, Set.of("limits"));
                return new Feature(key, resultSet.getBoolean("enabled"), null, false, limits(config.get("limits")));
            }
            if (ORDER_TYPE_KEYS.contains(key)) {
                requireExactFields(config, Set.of("label", "default"));
                return new Feature(
                        key,
                        resultSet.getBoolean("enabled"),
                        text(config, "label"),
                        bool(config, "default"),
                        null);
            }
            requireExactFields(config, Set.of());
            return new Feature(key, resultSet.getBoolean("enabled"), null, false, null);
        } catch (IllegalArgumentException exception) {
            throw invalidPersistedState();
        }
    }

    private DesignTokens designTokens(JsonNode node) {
        requireExactFields(node, DESIGN_TOKEN_FIELDS);
        return new DesignTokens(
                text(node, "primaryColor"),
                text(node, "secondaryColor"),
                text(node, "accentColor"),
                text(node, "backgroundColor"),
                text(node, "surfaceColor"),
                text(node, "textColor"),
                text(node, "mutedTextColor"),
                text(node, "borderRadius"),
                text(node, "fontFamily"));
    }

    private List<LocalizedText> localizedTexts(JsonNode node) {
        var result = new ArrayList<LocalizedText>();
        for (var value : node) {
            requireObject(value);
            requireExactFields(value, LOCALIZED_TEXT_FIELDS);
            result.add(new LocalizedText(
                    text(value, "locale"),
                    text(value, "displayName"),
                    text(value, "shortName"),
                    text(value, "businessTypeLabel"),
                    text(value, "cuisineText"),
                    text(value, "slogan"),
                    text(value, "introText"),
                    text(value, "menuSectionTitle"),
                    text(value, "cartTitle"),
                    text(value, "orderTypeLegend")));
        }
        return List.copyOf(result);
    }

    private Hero hero(JsonNode node) {
        requireAllowedFields(node, HERO_FIELDS);
        var benefits = node.get("benefits");
        if (benefits == null || !benefits.isArray()) {
            throw invalidPersistedState();
        }
        var mappedBenefits = new ArrayList<HeroBenefit>();
        for (var benefit : benefits) {
            requireObject(benefit);
            requireExactFields(benefit, BENEFIT_FIELDS);
            mappedBenefits.add(new HeroBenefit(text(benefit, "icon"), text(benefit, "text")));
        }
        return new Hero(
                optionalAssetId(node, "imageAssetId"),
                optionalText(node, "imageAlt"),
                optionalText(node, "eyebrow"),
                text(node, "title"),
                optionalText(node, "subtitle"),
                text(node, "ctaLabel"),
                mappedBenefits);
    }

    private SeoMetadata seo(JsonNode node) {
        requireAllowedFields(node, SEO_FIELDS);
        return new SeoMetadata(
                text(node, "title"),
                text(node, "description"),
                text(node, "canonicalUrl"),
                optionalAssetId(node, "openGraphAssetId"));
    }

    private List<WorkingHoursEntry> workingHours(JsonNode node) {
        var result = new ArrayList<WorkingHoursEntry>();
        for (var value : node) {
            requireObject(value);
            requireExactFields(value, WORKING_HOURS_FIELDS);
            result.add(new WorkingHoursEntry(
                    text(value, "day"), text(value, "opensAt"), text(value, "closesAt")));
        }
        return List.copyOf(result);
    }

    private List<SocialLink> socialLinks(JsonNode node) {
        var result = new ArrayList<SocialLink>();
        for (var value : node) {
            requireObject(value);
            requireExactFields(value, SOCIAL_LINK_FIELDS);
            result.add(new SocialLink(text(value, "type"), text(value, "label"), text(value, "url")));
        }
        return List.copyOf(result);
    }

    private OrderLimits limits(JsonNode node) {
        requireObject(node);
        var required = Set.of(
                "maxItemQuantity", "maxTotalItems", "maxNameLength", "maxPhoneLength",
                "maxAddressLength", "maxCommentLength", "delayedOrderConfirmationMinutes");
        var allowed = new java.util.HashSet<>(required);
        allowed.add("minOrderMinor");
        allowed.add("freeDeliveryThresholdMinor");
        requireAllowedFields(node, allowed);
        if (!fieldNames(node).containsAll(required)) {
            throw invalidPersistedState();
        }
        return new OrderLimits(
                integer(node, "maxItemQuantity"),
                integer(node, "maxTotalItems"),
                integer(node, "maxNameLength"),
                integer(node, "maxPhoneLength"),
                integer(node, "maxAddressLength"),
                integer(node, "maxCommentLength"),
                optionalLong(node, "minOrderMinor"),
                optionalLong(node, "freeDeliveryThresholdMinor"),
                integer(node, "delayedOrderConfirmationMinutes"));
    }

    private JsonNode object(String json, Set<String> allowedFields) {
        try {
            var node = objectMapper.readTree(json);
            requireObject(node);
            requireAllowedFields(node, allowedFields);
            return node;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw invalidPersistedState();
        }
    }

    private JsonNode array(String json) {
        try {
            var node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) {
                throw invalidPersistedState();
            }
            return node;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw invalidPersistedState();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Validated tenant configuration could not be serialized", exception);
        }
    }

    private Map<String, Object> designTokensMap(DesignTokens tokens) {
        var value = new LinkedHashMap<String, Object>();
        value.put("primaryColor", tokens.primaryColor());
        value.put("secondaryColor", tokens.secondaryColor());
        value.put("accentColor", tokens.accentColor());
        value.put("backgroundColor", tokens.backgroundColor());
        value.put("surfaceColor", tokens.surfaceColor());
        value.put("textColor", tokens.textColor());
        value.put("mutedTextColor", tokens.mutedTextColor());
        value.put("borderRadius", tokens.borderRadius());
        value.put("fontFamily", tokens.fontFamily());
        return value;
    }

    private Map<String, Object> localizedTextMap(LocalizedText text) {
        var value = new LinkedHashMap<String, Object>();
        value.put("locale", text.locale());
        value.put("displayName", text.displayName());
        value.put("shortName", text.shortName());
        value.put("businessTypeLabel", text.businessTypeLabel());
        value.put("cuisineText", text.cuisineText());
        value.put("slogan", text.slogan());
        value.put("introText", text.introText());
        value.put("menuSectionTitle", text.menuSectionTitle());
        value.put("cartTitle", text.cartTitle());
        value.put("orderTypeLegend", text.orderTypeLegend());
        return value;
    }

    private Map<String, Object> heroMap(Hero hero) {
        var value = new LinkedHashMap<String, Object>();
        putIfPresent(value, "imageAssetId", uuidText(hero.imageAssetId()));
        putIfPresent(value, "imageAlt", hero.imageAlt());
        putIfPresent(value, "eyebrow", hero.eyebrow());
        value.put("title", hero.title());
        putIfPresent(value, "subtitle", hero.subtitle());
        value.put("ctaLabel", hero.ctaLabel());
        value.put("benefits", hero.benefits().stream().map(benefit -> Map.of(
                "icon", benefit.icon(),
                "text", benefit.text())).toList());
        return value;
    }

    private Map<String, Object> seoMap(SeoMetadata seo) {
        var value = new LinkedHashMap<String, Object>();
        value.put("title", seo.title());
        value.put("description", seo.description());
        value.put("canonicalUrl", seo.canonicalUrl());
        putIfPresent(value, "openGraphAssetId", uuidText(seo.openGraphAssetId()));
        return value;
    }

    private Map<String, Object> workingHoursMap(WorkingHoursEntry entry) {
        return Map.of("day", entry.day(), "opensAt", entry.opensAt(), "closesAt", entry.closesAt());
    }

    private Map<String, Object> socialLinkMap(SocialLink link) {
        return Map.of("type", link.type(), "label", link.label(), "url", link.url());
    }

    private Map<String, Object> featureConfig(Feature feature) {
        if (feature.key() == TenantFeatureKey.ORDERS) {
            var limits = feature.limits();
            var limitValues = new LinkedHashMap<String, Object>();
            limitValues.put("maxItemQuantity", limits.maxItemQuantity());
            limitValues.put("maxTotalItems", limits.maxTotalItems());
            limitValues.put("maxNameLength", limits.maxNameLength());
            limitValues.put("maxPhoneLength", limits.maxPhoneLength());
            limitValues.put("maxAddressLength", limits.maxAddressLength());
            limitValues.put("maxCommentLength", limits.maxCommentLength());
            putIfPresent(limitValues, "minOrderMinor", limits.minOrderMinor());
            putIfPresent(limitValues, "freeDeliveryThresholdMinor", limits.freeDeliveryThresholdMinor());
            limitValues.put("delayedOrderConfirmationMinutes", limits.delayedOrderConfirmationMinutes());
            return Map.of("limits", limitValues);
        }
        if (ORDER_TYPE_KEYS.contains(feature.key())) {
            return Map.of("label", feature.label(), "default", feature.defaultOrderType());
        }
        return Map.of();
    }

    private static Set<String> featureFields(TenantFeatureKey key) {
        if (key == TenantFeatureKey.ORDERS) {
            return Set.of("limits");
        }
        if (ORDER_TYPE_KEYS.contains(key)) {
            return Set.of("label", "default");
        }
        return Set.of();
    }

    private static boolean putExpectedAsset(Map<UUID, String> expected, TenantAssetId id, String kind) {
        if (id == null) {
            return true;
        }
        var previous = expected.putIfAbsent(id.value(), kind);
        return previous == null || previous.equals(kind);
    }

    private static TenantAssetId assetId(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column, UUID.class);
        return value == null ? null : new TenantAssetId(value);
    }

    private static TenantAssetId optionalAssetId(JsonNode node, String field) {
        var value = optionalText(node, field);
        if (value == null) {
            return null;
        }
        try {
            return new TenantAssetId(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            throw invalidPersistedState();
        }
    }

    private static UUID uuid(TenantAssetId value) {
        return value == null ? null : value.value();
    }

    private static String uuidText(TenantAssetId value) {
        return value == null ? null : value.value().toString();
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalidPersistedState();
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalidPersistedState();
        }
        return value.textValue();
    }

    private static boolean bool(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalidPersistedState();
        }
        return value.booleanValue();
    }

    private static int integer(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalidPersistedState();
        }
        return value.intValue();
    }

    private static Long optionalLong(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalidPersistedState();
        }
        return value.longValue();
    }

    private static void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalidPersistedState();
        }
    }

    private static void requireExactFields(JsonNode node, Set<String> expected) {
        if (!fieldNames(node).equals(expected)) {
            throw invalidPersistedState();
        }
    }

    private static void requireAllowedFields(JsonNode node, Set<String> allowed) {
        if (!allowed.containsAll(fieldNames(node))) {
            throw invalidPersistedState();
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        var result = new java.util.HashSet<String>();
        node.properties().forEach(property -> result.add(property.getKey()));
        return Set.copyOf(result);
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void requireOne(int affectedRows) {
        if (affectedRows != 1) {
            throw invalidPersistedState();
        }
    }

    private static TenantProvisioningException invalidPersistedState() {
        return new TenantProvisioningException(Code.PERSISTED_STATE_INVALID);
    }
}
