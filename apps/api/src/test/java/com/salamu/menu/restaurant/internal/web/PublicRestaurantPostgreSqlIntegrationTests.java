package com.salamu.menu.restaurant.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.Filter;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest
class PublicRestaurantPostgreSqlIntegrationTests {

    private static final UUID TENANT_A = uuid("10000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_B = uuid("20000000-0000-4000-8000-000000000002");
    private static final UUID RESTAURANT_A = uuid("11000000-0000-4000-8000-000000000001");
    private static final UUID RESTAURANT_B = uuid("21000000-0000-4000-8000-000000000002");
    private static final UUID LOGO_A = uuid("12000000-0000-4000-8000-000000000001");
    private static final UUID FAVICON_A = uuid("13000000-0000-4000-8000-000000000001");
    private static final UUID HERO_A = uuid("14000000-0000-4000-8000-000000000001");
    private static final UUID OG_A = uuid("15000000-0000-4000-8000-000000000001");
    private static final UUID LOGO_B = uuid("22000000-0000-4000-8000-000000000002");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine3.21")
            .withDatabaseName("public_restaurant")
            .withUsername("public_restaurant")
            .withPassword("public_restaurant");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var filters = new ArrayList<>(applicationContext.getBeansOfType(Filter.class).values());
        AnnotationAwareOrderComparator.sort(filters);
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .addFilters(filters.toArray(Filter[]::new))
                .build();

        jdbcTemplate.execute("""
                TRUNCATE TABLE notification_deliveries, outbox_events, notification_destinations,
                    order_status_history, order_items, orders, menu_items, menu_categories, menu_versions,
                    tenant_branding, tenant_profiles, tenant_features, tenant_assets, tenant_order_counters,
                    restaurants, tenant_domains, tenants CASCADE
                """);
        insertFixtures();
    }

    @Test
    void returnsExactPublicShapeHeadersAndNoPrivateOrPersistenceFields() throws Exception {
        var result = mockMvc.perform(get("/api/v1/public/stambul/restaurant").header("Host", "example.test"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Cache-Control",
                        "public, max-age=60, s-maxage=300, stale-while-revalidate=60"))
                .andExpect(header().string("Cache-Tag", "tenant-stambul"))
                .andExpect(header().string("Surrogate-Key", "tenant-stambul restaurant-stambul"))
                .andExpect(header().string("ETag", org.hamcrest.Matchers.matchesPattern("\"[0-9a-f]{64}\"")))
                .andExpect(jsonPath("$.restaurantId").value(RESTAURANT_A.toString()))
                .andExpect(jsonPath("$.slug").value("stambul"))
                .andExpect(jsonPath("$.branding.logoUrl").value(
                        "https://assets.example.test/assets/" + TENANT_A + "/logo/" + "a".repeat(64) + ".png"))
                .andExpect(jsonPath("$.branding.hero.imageUrl").value(
                        "https://assets.example.test/assets/" + TENANT_A + "/hero/" + "c".repeat(64) + ".png"))
                .andExpect(jsonPath("$.seo.openGraphImageUrl").value(
                        "https://assets.example.test/assets/" + TENANT_A + "/open_graph/" + "d".repeat(64) + ".png"))
                .andExpect(jsonPath("$.features.deliveryBlocks").value(false))
                .andExpect(jsonPath("$.orderTypes.length()").value(3))
                .andExpect(jsonPath("$.orderTypes[0].code").value("preorder"))
                .andExpect(jsonPath("$.limits.minOrderMinor").doesNotExist())
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andReturn();

        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "restaurantId", "slug", "branding", "seo", "profile", "features",
                "orderTypes", "limits", "locale", "currency", "timezone");
        assertThat(fieldNames(json.get("branding"))).containsExactlyInAnyOrder(
                "logoUrl", "logoAlt", "faviconUrl", "hero", "designTokens", "localizedTexts");
        assertThat(fieldNames(json.get("features"))).containsExactlyInAnyOrder(
                "orders", "contacts", "geoLocation", "socialLinks", "delivery", "pickup", "preorder",
                "customTheme", "whatsappFallback", "promotions", "loyalty", "reservations", "deliveryBlocks");
        var raw = result.getResponse().getContentAsString();
        assertThat(raw)
                .doesNotContain("managerPhone")
                .doesNotContain("publicEmail")
                .doesNotContain("secret_reference")
                .doesNotContain("vault://")
                .doesNotContain("object_key")
                .doesNotContain("/tenants/");
    }

    @Test
    void isolatesTenantDataAndOmitsAbsentOptionalNonNullableFields() throws Exception {
        var result = mockMvc.perform(get("/api/v1/public/adamas/restaurant").header("Host", "example.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantId").value(RESTAURANT_B.toString()))
                .andExpect(jsonPath("$.features.deliveryBlocks").value(true))
                .andExpect(jsonPath("$.limits.minOrderMinor").value(50000))
                .andExpect(jsonPath("$.limits.freeDeliveryThresholdMinor").value(100000))
                .andExpect(jsonPath("$.branding.faviconUrl").doesNotExist())
                .andExpect(jsonPath("$.branding.hero.imageUrl").doesNotExist())
                .andExpect(jsonPath("$.branding.hero.imageAlt").doesNotExist())
                .andExpect(jsonPath("$.seo.openGraphImageUrl").doesNotExist())
                .andExpect(jsonPath("$.profile.address").doesNotExist())
                .andExpect(jsonPath("$.profile.location").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("assets/" + TENANT_B + "/logo/" + "e".repeat(64) + ".png")
                .doesNotContain("/tenants/")
                .doesNotContain("assets/" + TENANT_A);
    }

    @Test
    void stableEtagProducesBodyless304WithSameTenantScopedCacheHeaders() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/v1/public/stambul/restaurant").header("Host", "example.test"))
                .andExpect(status().isOk())
                .andReturn();
        var entityTag = first.getResponse().getHeader("ETag");

        mockMvc.perform(get("/api/v1/public/stambul/restaurant")
                        .header("Host", "example.test")
                        .header("If-None-Match", "W/" + entityTag))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""))
                .andExpect(header().string("ETag", entityTag))
                .andExpect(header().string("Cache-Tag", "tenant-stambul"))
                .andExpect(header().string("Surrogate-Key", "tenant-stambul restaurant-stambul"))
                .andExpect(header().string(
                        "Cache-Control",
                        "public, max-age=60, s-maxage=300, stale-while-revalidate=60"));
    }

    @Test
    void publicValueMutationChangesEtagEvenWhenPersistenceTimestampIsUnchanged() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/v1/public/stambul/restaurant").header("Host", "example.test"))
                .andExpect(status().isOk())
                .andReturn();
        var firstTag = first.getResponse().getHeader("ETag");
        jdbcTemplate.update(
                "UPDATE tenant_branding SET display_name = 'Изменённое имя' WHERE tenant_id = ?",
                TENANT_A);

        MvcResult changed = mockMvc.perform(get("/api/v1/public/stambul/restaurant")
                        .header("Host", "example.test")
                        .header("If-None-Match", firstTag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branding.logoAlt").value("Изменённое имя"))
                .andReturn();

        assertThat(changed.getResponse().getHeader("ETag")).isNotEqualTo(firstTag);
    }

    @Test
    void missingRequiredBrandingOrProfileIsGeneric500ForAnOtherwiseActiveConfiguration() throws Exception {
        jdbcTemplate.update("DELETE FROM tenant_branding WHERE tenant_id = ?", TENANT_A);
        jdbcTemplate.update("DELETE FROM tenant_profiles WHERE tenant_id = ?", TENANT_B);

        mockMvc.perform(get("/api/v1/public/stambul/restaurant").header("Host", "example.test"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(jsonPath("$.tenantId").doesNotExist());
        mockMvc.perform(get("/api/v1/public/adamas/restaurant").header("Host", "example.test"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    @Test
    void unknownAndInactiveTenantsRemainControlled404() throws Exception {
        mockMvc.perform(get("/api/v1/public/unknown/restaurant").header("Host", "example.test"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.tenantId").doesNotExist());

        jdbcTemplate.update("UPDATE tenants SET status = 'suspended' WHERE id = ?", TENANT_B);
        mockMvc.perform(get("/api/v1/public/adamas/restaurant").header("Host", "example.test"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    @Test
    void malformedPersistedLimitsReturnGeneric500WithoutConfigurationDetails() throws Exception {
        jdbcTemplate.update(
                """
                UPDATE tenant_features
                   SET config = '{"limits":{"maxItemQuantity":0}}'::jsonb,
                       updated_at = now()
                 WHERE tenant_id = ? AND feature_key = 'orders'
                """,
                TENANT_A);

        var result = mockMvc.perform(get("/api/v1/public/stambul/restaurant").header("Host", "example.test"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("maxItemQuantity")
                .doesNotContain("tenant_features")
                .doesNotContain(TENANT_A.toString());
    }

    @Test
    void fractionalAndOverflowingStoredLimitNumbersFailClosed() throws Exception {
        jdbcTemplate.update(
                """
                UPDATE tenant_features
                   SET config = '{"limits":{"maxItemQuantity":1.5,"maxTotalItems":200,"maxNameLength":80,"maxPhoneLength":32,"maxAddressLength":240,"maxCommentLength":500,"delayedOrderConfirmationMinutes":15}}'::jsonb
                 WHERE tenant_id = ? AND feature_key = 'orders'
                """,
                TENANT_A);
        jdbcTemplate.update(
                """
                UPDATE tenant_features
                   SET config = '{"limits":{"maxItemQuantity":2147483648,"maxTotalItems":200,"maxNameLength":80,"maxPhoneLength":32,"maxAddressLength":240,"maxCommentLength":500,"delayedOrderConfirmationMinutes":15}}'::jsonb
                 WHERE tenant_id = ? AND feature_key = 'orders'
                """,
                TENANT_B);

        assertGenericConfiguration500("stambul");
        assertGenericConfiguration500("adamas");
    }

    @Test
    void fractionalAndOverflowingStoredMoneyLimitsFailClosed() throws Exception {
        jdbcTemplate.update(
                """
                UPDATE tenant_features
                   SET config = '{"limits":{"maxItemQuantity":99,"maxTotalItems":200,"maxNameLength":80,"maxPhoneLength":32,"maxAddressLength":240,"maxCommentLength":500,"minOrderMinor":1.5,"delayedOrderConfirmationMinutes":15}}'::jsonb
                 WHERE tenant_id = ? AND feature_key = 'orders'
                """,
                TENANT_A);
        jdbcTemplate.update(
                """
                UPDATE tenant_features
                   SET config = '{"limits":{"maxItemQuantity":99,"maxTotalItems":200,"maxNameLength":80,"maxPhoneLength":32,"maxAddressLength":240,"maxCommentLength":500,"freeDeliveryThresholdMinor":9223372036854775808,"delayedOrderConfirmationMinutes":15}}'::jsonb
                 WHERE tenant_id = ? AND feature_key = 'orders'
                """,
                TENANT_B);

        assertGenericConfiguration500("stambul");
        assertGenericConfiguration500("adamas");
    }

    @Test
    void invalidIanaTimezoneFailsClosed() throws Exception {
        jdbcTemplate.update("UPDATE tenants SET timezone = 'Mars/Olympus' WHERE id = ?", TENANT_A);

        assertGenericConfiguration500("stambul");
    }

    @Test
    void maximumLengthDisplayNameRemainsAValidLogoAlt() throws Exception {
        var displayName = "Я".repeat(160);
        jdbcTemplate.update(
                "UPDATE tenant_branding SET display_name = ? WHERE tenant_id = ?",
                displayName,
                TENANT_A);

        mockMvc.perform(get("/api/v1/public/stambul/restaurant").header("Host", "example.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branding.logoAlt").value(displayName));
    }

    private void assertGenericConfiguration500(String tenantSlug) throws Exception {
        mockMvc.perform(get("/api/v1/public/{tenantSlug}/restaurant", tenantSlug)
                        .header("Host", "example.test"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    private void insertFixtures() {
        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, slug, status, locale, currency, timezone)
                VALUES (?, 'stambul', 'active', 'ru-RU', 'RUB', 'Europe/Moscow'),
                       (?, 'adamas', 'active', 'ru-RU', 'RUB', 'Europe/Moscow')
                """,
                TENANT_A,
                TENANT_B);
        insertAsset(TENANT_A, LOGO_A, "logo", "a");
        insertAsset(TENANT_A, FAVICON_A, "favicon", "b");
        insertAsset(TENANT_A, HERO_A, "hero", "c");
        insertAsset(TENANT_A, OG_A, "open_graph", "d");
        insertAsset(TENANT_B, LOGO_B, "logo", "e");
        jdbcTemplate.update(
                """
                INSERT INTO restaurants (id, tenant_id, slug, display_name, status, is_default)
                VALUES (?, ?, 'main', 'Общее имя', 'active', true),
                       (?, ?, 'main', 'Общее имя', 'active', true)
                """,
                RESTAURANT_A, TENANT_A, RESTAURANT_B, TENANT_B);
        insertBranding(
                TENANT_A,
                LOGO_A,
                FAVICON_A,
                """
                {"imageAssetId":"%s","imageAlt":"Блюдо","eyebrow":"Меню","title":"Вкусно","subtitle":"Кухня","ctaLabel":"Смотреть","benefits":[]}
                """.formatted(HERO_A),
                """
                {"title":"Ресторан","description":"Описание","canonicalUrl":"https://stambul.example.test/","openGraphAssetId":"%s"}
                """.formatted(OG_A));
        insertBranding(
                TENANT_B,
                LOGO_B,
                null,
                """
                {"title":"Вкусно","ctaLabel":"Смотреть","benefits":[]}
                """,
                """
                {"title":"Ресторан","description":"Описание","canonicalUrl":"https://adamas.example.test/"}
                """);
        jdbcTemplate.update(
                """
                INSERT INTO tenant_profiles (
                    tenant_id, public_phone, public_phone_label, public_email, address_text,
                    working_hours_text, latitude, longitude, social_links
                ) VALUES
                    (?, '+7 (999) 000-00-00', 'Позвонить', 'private@example.test', 'Москва',
                     'Ежедневно', 55.750000, 37.620000,
                     '[{"type":"telegram","label":"Telegram","url":"https://t.me/public"}]'::jsonb),
                    (?, '+7 (999) 000-00-00', 'Позвонить', 'private-2@example.test', null,
                     'Ежедневно', null, null, '[]'::jsonb)
                """,
                TENANT_A,
                TENANT_B);
        insertFeatures(TENANT_A, false, false);
        insertFeatures(TENANT_B, true, true);
        jdbcTemplate.update(
                """
                INSERT INTO notification_destinations (
                    id, tenant_id, channel, status, display_name, secret_reference, metadata
                ) VALUES (?, ?, 'whatsapp', 'active', 'manager', 'vault://tenant/manager',
                          '{"managerPhone":"+79991112233"}'::jsonb)
                """,
                uuid("16000000-0000-4000-8000-000000000001"),
                TENANT_A);
    }

    private void insertBranding(
            UUID tenantId,
            UUID logoId,
            UUID faviconId,
            String hero,
            String seo) {
        jdbcTemplate.update(
                """
                INSERT INTO tenant_branding (
                    tenant_id, display_name, business_type, logo_asset_id, favicon_asset_id,
                    design_tokens, localized_texts, hero, seo_metadata
                ) VALUES (?, 'Общее имя', 'Ресторан', ?, ?,
                    '{"primaryColor":"#111111","secondaryColor":"#222222","accentColor":"#333333","backgroundColor":"#444444","surfaceColor":"#555555","textColor":"#666666","mutedTextColor":"#777777","borderRadius":"1rem","fontFamily":"Inter"}'::jsonb,
                    '[{"locale":"ru-RU","displayName":"Общее имя","shortName":"Общее","businessTypeLabel":"Ресторан","cuisineText":"Кухня","slogan":"Вкусно","introText":"Описание","menuSectionTitle":"Меню","cartTitle":"Корзина","orderTypeLegend":"Получение"}]'::jsonb,
                    ?::jsonb, ?::jsonb)
                """,
                tenantId,
                logoId,
                faviconId,
                hero,
                seo);
    }

    private void insertFeatures(UUID tenantId, boolean deliveryBlocks, boolean withThresholds) {
        var limits = withThresholds
                ? """
                  {"limits":{"maxItemQuantity":99,"maxTotalItems":200,"maxNameLength":80,"maxPhoneLength":32,"maxAddressLength":240,"maxCommentLength":500,"minOrderMinor":50000,"freeDeliveryThresholdMinor":100000,"delayedOrderConfirmationMinutes":15}}
                  """
                : """
                  {"limits":{"maxItemQuantity":99,"maxTotalItems":200,"maxNameLength":80,"maxPhoneLength":32,"maxAddressLength":240,"maxCommentLength":500,"delayedOrderConfirmationMinutes":15}}
                  """;
        insertFeature(tenantId, "orders", true, limits);
        insertFeature(tenantId, "contacts", true, "{}");
        insertFeature(tenantId, "geo_location", !deliveryBlocks, "{}");
        insertFeature(tenantId, "social_links", !deliveryBlocks, "{}");
        insertFeature(tenantId, "preorder", true, "{\"label\":\"Предзаказ\",\"default\":true}");
        insertFeature(tenantId, "delivery", true, "{\"label\":\"Доставка\",\"default\":false}");
        insertFeature(tenantId, "pickup", true, "{\"label\":\"Самовывоз\",\"default\":false}");
        insertFeature(tenantId, "custom_theme", true, "{}");
        insertFeature(tenantId, "whatsapp_fallback", true, "{}");
        insertFeature(tenantId, "delivery_blocks", deliveryBlocks, "{}");
    }

    private void insertFeature(UUID tenantId, String key, boolean enabled, String config) {
        jdbcTemplate.update(
                "INSERT INTO tenant_features (tenant_id, feature_key, enabled, config) VALUES (?, ?, ?, ?::jsonb)",
                tenantId,
                key,
                enabled,
                config);
    }

    private void insertAsset(UUID tenantId, UUID id, String type, String hashCharacter) {
        var sha256 = hashCharacter.repeat(64);
        var objectKey = "tenants/" + tenantId + "/" + type + "/" + sha256 + ".png";
        jdbcTemplate.update(
                """
                INSERT INTO tenant_assets (
                    id, tenant_id, object_key, asset_type, content_type, size_bytes, sha256, status
                ) VALUES (?, ?, ?, ?, 'image/png', 10, ?, 'active')
                """,
                id,
                tenantId,
                objectKey,
                type,
                sha256);
    }

    private static Set<String> fieldNames(tools.jackson.databind.JsonNode node) {
        var result = new java.util.LinkedHashSet<String>();
        node.properties().forEach(property -> result.add(property.getKey()));
        return result;
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
