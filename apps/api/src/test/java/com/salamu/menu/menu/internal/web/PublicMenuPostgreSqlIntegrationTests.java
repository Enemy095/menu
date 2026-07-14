package com.salamu.menu.menu.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.Filter;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpHeaders;
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
class PublicMenuPostgreSqlIntegrationTests {

    private static final String TENANT_A = "10000000-0000-4000-8000-000000000001";
    private static final String TENANT_B = "20000000-0000-4000-8000-000000000002";
    private static final String TENANT_C = "30000000-0000-4000-8000-000000000003";
    private static final String MENU_A = "11110000-0000-4000-8000-000000000001";
    private static final String ASSET_CATEGORY_A = "fa000000-0000-4000-8000-000000000001";
    private static final String ASSET_ITEM_A = "fa000000-0000-4000-8000-000000000002";
    private static final String ASSET_DELETED_A = "fa000000-0000-4000-8000-000000000003";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine3.21")
            .withDatabaseName("public_menu")
            .withUsername("public_menu")
            .withPassword("public_menu");

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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE tenants CASCADE");
        insertFixtures();

        var filters = new ArrayList<>(applicationContext.getBeansOfType(Filter.class).values());
        AnnotationAwareOrderComparator.sort(filters);
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .addFilters(filters.toArray(Filter[]::new))
                .build();
    }

    @Test
    void returnsOnlyCurrentPublishedVisibleMenuInExactContractOrderAndShape() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/public/stambul/menu")
                        .header(HttpHeaders.HOST, "example.test"))
                .andExpect(status().isOk())
                .andExpect(mvcResult -> assertThat(mvcResult.getResponse().getHeader(HttpHeaders.ETAG))
                        .matches("\\\"menu-stambul-v3-[0-9a-f]{64}\\\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PublicMenuController.CACHE_CONTROL))
                .andExpect(header().string(PublicMenuController.CACHE_TAG_HEADER, "tenant-stambul"))
                .andExpect(header().string(PublicMenuController.SURROGATE_KEY_HEADER, "tenant-stambul"))
                .andExpect(jsonPath("$.restaurantId")
                        .value("11000000-0000-4000-8000-000000000001"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.publishedAt").value("2026-07-14T09:00:00Z"))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.categories.length()").value(3))
                .andExpect(jsonPath("$.items.length()").value(4))
                .andReturn();

        var body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<String>>read(body, "$.categories[*].categoryId"))
                .containsExactly("burgers", "rolls", "pizza");
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].menuItemId"))
                .containsExactly("burger-classic", "roll-a", "roll-z", "pizza-pepperoni")
                .doesNotContain("hidden-secret", "draft-only", "archived-only");
        assertThat(JsonPath.<Number>read(body, "$.items[3].priceMinor").longValue())
                .isEqualTo(4_000_000_000L);
        assertThat(JsonPath.<Boolean>read(body, "$.items[1].available")).isFalse();
        assertThat(JsonPath.<String>read(body, "$.categories[2].imageUrl"))
                .isEqualTo("https://assets.example.test/assets/" + TENANT_A
                        + "/menu_category_image/" + "a".repeat(64) + ".jpg");
        assertThat(JsonPath.<String>read(body, "$.items[3].imageUrl"))
                .isEqualTo("https://assets.example.test/assets/" + TENANT_A
                        + "/menu_item_image/" + "b".repeat(64) + ".jpg");

        @SuppressWarnings("unchecked")
        var root = (Map<String, Object>) JsonPath.read(body, "$");
        assertThat(root.keySet()).containsExactlyInAnyOrder(
                "restaurantId", "version", "publishedAt", "currency", "categories", "items");
        @SuppressWarnings("unchecked")
        var categoryWithoutOptionals = (Map<String, Object>) JsonPath.read(body, "$.categories[0]");
        assertThat(categoryWithoutOptionals.keySet())
                .containsExactlyInAnyOrder("categoryId", "name", "sortOrder");
        @SuppressWarnings("unchecked")
        var itemWithoutOptionals = (Map<String, Object>) JsonPath.read(body, "$.items[0]");
        assertThat(itemWithoutOptionals.keySet()).containsExactlyInAnyOrder(
                "menuItemId", "categoryId", "name", "priceMinor", "available", "sortOrder");
        assertThat(body)
                .doesNotContain("tenantId", "objectKey", "description\":null", "imageUrl\":null")
                .doesNotContain(ASSET_CATEGORY_A, ASSET_ITEM_A, ASSET_DELETED_A);
    }

    @Test
    void identicalPublicIdsRemainIsolatedToTheTrustedTenant() throws Exception {
        var stambul = body(getMenu("stambul"));
        var adamas = body(getMenu("adamas"));

        assertThat(JsonPath.<String>read(stambul, "$.items[3].name")).isEqualTo("Пепперони Stambul");
        assertThat(JsonPath.<Number>read(stambul, "$.items[3].priceMinor").longValue())
                .isEqualTo(4_000_000_000L);
        assertThat(JsonPath.<String>read(adamas, "$.items[0].menuItemId"))
                .isEqualTo("pizza-pepperoni");
        assertThat(JsonPath.<String>read(adamas, "$.items[0].name")).isEqualTo("Пепперони Adamas");
        assertThat(JsonPath.<Number>read(adamas, "$.items[0].priceMinor").longValue())
                .isEqualTo(59_000L);
        assertThat(adamas).doesNotContain("Stambul", TENANT_A);
        assertThat(stambul).doesNotContain("Adamas", TENANT_B);
    }

    @Test
    void noPublishedMenuReturnsControlledContract404() throws Exception {
        mockMvc.perform(get("/api/v1/public/no-menu/menu")
                        .header(HttpHeaders.HOST, "example.test"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    @Test
    void matchingIfNoneMatchReturns304WithTheSameTenantScopedCacheMetadata() throws Exception {
        var etag = getMenu("stambul").getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(get("/api/v1/public/stambul/menu")
                        .header(HttpHeaders.HOST, "example.test")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"unrelated\", W/" + etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PublicMenuController.CACHE_CONTROL))
                .andExpect(header().string(PublicMenuController.CACHE_TAG_HEADER, "tenant-stambul"))
                .andExpect(header().string(PublicMenuController.SURROGATE_KEY_HEADER, "tenant-stambul"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isEmpty());
    }

    @Test
    void strongEtagIsStableAndChangesWithEveryPublicRepresentationMutation() throws Exception {
        var baseline = etag(getMenu("stambul"));
        var identical = etag(getMenu("stambul"));

        jdbcTemplate.update("""
                UPDATE menu_items
                   SET name = 'Изменённая позиция'
                 WHERE tenant_id = ?::uuid
                   AND menu_version_id = ?::uuid
                   AND public_id = 'pizza-pepperoni'
                """, TENANT_A, MENU_A);
        var itemChanged = etag(getMenu("stambul"));

        jdbcTemplate.update("""
                UPDATE menu_categories
                   SET name = 'Изменённая категория'
                 WHERE tenant_id = ?::uuid
                   AND menu_version_id = ?::uuid
                   AND public_id = 'pizza'
                """, TENANT_A, MENU_A);
        var categoryChanged = etag(getMenu("stambul"));

        jdbcTemplate.update("""
                UPDATE tenant_assets
                   SET object_key = ?, sha256 = ?
                 WHERE tenant_id = ?::uuid
                   AND id = ?::uuid
                """, "tenants/" + TENANT_A + "/menu_item_image/" + "e".repeat(64) + ".jpg",
                "e".repeat(64), TENANT_A, ASSET_ITEM_A);
        var assetChanged = etag(getMenu("stambul"));

        jdbcTemplate.update("""
                UPDATE menu_items
                   SET available = false
                 WHERE tenant_id = ?::uuid
                   AND menu_version_id = ?::uuid
                   AND public_id = 'burger-classic'
                """, TENANT_A, MENU_A);
        var availabilityChanged = etag(getMenu("stambul"));

        jdbcTemplate.update("UPDATE tenants SET currency = 'USD' WHERE id = ?::uuid", TENANT_A);
        jdbcTemplate.update("""
                UPDATE menu_items
                   SET currency = 'USD'
                 WHERE tenant_id = ?::uuid
                   AND menu_version_id = ?::uuid
                """, TENANT_A, MENU_A);
        var currencyChanged = etag(getMenu("stambul"));

        assertThat(identical).isEqualTo(baseline);
        assertThat(List.of(itemChanged, categoryChanged, assetChanged, availabilityChanged, currencyChanged))
                .doesNotHaveDuplicates()
                .doesNotContain(baseline);
    }

    @Test
    void mixedPublishedCurrencyFailsClosedWithoutLeakingPersistenceDetails() throws Exception {
        jdbcTemplate.update("""
                UPDATE menu_items
                   SET currency = 'USD'
                 WHERE tenant_id = ?::uuid
                   AND menu_version_id = ?::uuid
                   AND public_id = 'pizza-pepperoni'
                """, TENANT_A, MENU_A);

        var result = mockMvc.perform(get("/api/v1/public/stambul/menu")
                        .header(HttpHeaders.HOST, "example.test"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.details").isArray())
                .andReturn();

        assertThat(body(result)).doesNotContain("USD", TENANT_A, MENU_A, "menu_items");
    }

    private MvcResult getMenu(String slug) throws Exception {
        return mockMvc.perform(get("/api/v1/public/{tenantSlug}/menu", slug)
                        .header(HttpHeaders.HOST, "example.test"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String etag(MvcResult result) {
        return result.getResponse().getHeader(HttpHeaders.ETAG);
    }

    private void insertFixtures() {
        jdbcTemplate.update("""
                INSERT INTO tenants (id, slug, status, locale, currency, timezone)
                VALUES
                    (?::uuid, 'stambul', 'active', 'ru-RU', 'RUB', 'Europe/Moscow'),
                    (?::uuid, 'adamas', 'active', 'ru-RU', 'RUB', 'Europe/Moscow'),
                    (?::uuid, 'no-menu', 'active', 'ru-RU', 'RUB', 'Europe/Moscow')
                """, TENANT_A, TENANT_B, TENANT_C);
        jdbcTemplate.update("""
                INSERT INTO restaurants (id, tenant_id, slug, display_name, status, is_default)
                VALUES
                    ('11000000-0000-4000-8000-000000000001', ?::uuid,
                     'main', 'Stambul', 'active', true),
                    ('22000000-0000-4000-8000-000000000002', ?::uuid,
                     'main', 'Adamas', 'active', true),
                    ('33000000-0000-4000-8000-000000000003', ?::uuid,
                     'main', 'No menu', 'active', true),
                    ('11000000-0000-4000-8000-000000000099', ?::uuid,
                     'inactive', 'Inactive Stambul', 'inactive', false)
                """, TENANT_A, TENANT_B, TENANT_C, TENANT_A);
        jdbcTemplate.update("""
                INSERT INTO menu_versions (
                    id, tenant_id, restaurant_id, version, status, published_at
                ) VALUES
                    (?::uuid, ?::uuid, '11000000-0000-4000-8000-000000000001',
                     3, 'published', '2026-07-14T09:00:00Z'),
                    ('11120000-0000-4000-8000-000000000001', ?::uuid,
                     '11000000-0000-4000-8000-000000000001', 4, 'draft', null),
                    ('11130000-0000-4000-8000-000000000001', ?::uuid,
                     '11000000-0000-4000-8000-000000000001', 2, 'archived', '2026-07-13T09:00:00Z'),
                    ('22210000-0000-4000-8000-000000000002', ?::uuid,
                     '22000000-0000-4000-8000-000000000002', 8, 'published', '2026-07-14T09:05:00Z'),
                    ('33310000-0000-4000-8000-000000000003', ?::uuid,
                     '33000000-0000-4000-8000-000000000003', 1, 'draft', null)
                """, MENU_A, TENANT_A, TENANT_A, TENANT_A, TENANT_B, TENANT_C);
        jdbcTemplate.update("""
                INSERT INTO tenant_assets (
                    id, tenant_id, object_key, asset_type, content_type,
                    size_bytes, sha256, status
                ) VALUES
                    (?::uuid, ?::uuid, ?, 'menu_category_image', 'image/jpeg',
                     100, ?, 'active'),
                    (?::uuid, ?::uuid, ?, 'menu_item_image', 'image/jpeg',
                     200, ?, 'active'),
                    (?::uuid, ?::uuid, ?, 'menu_item_image', 'image/jpeg',
                     300, ?, 'deleted'),
                    ('fb000000-0000-4000-8000-000000000001', ?::uuid,
                     ?, 'menu_item_image', 'image/jpeg', 400, ?, 'active')
                """,
                ASSET_CATEGORY_A, TENANT_A,
                "tenants/" + TENANT_A + "/menu_category_image/" + "a".repeat(64) + ".jpg", "a".repeat(64),
                ASSET_ITEM_A, TENANT_A,
                "tenants/" + TENANT_A + "/menu_item_image/" + "b".repeat(64) + ".jpg", "b".repeat(64),
                ASSET_DELETED_A, TENANT_A,
                "tenants/" + TENANT_A + "/menu_item_image/" + "c".repeat(64) + ".jpg", "c".repeat(64),
                TENANT_B, "tenants/" + TENANT_B + "/menu_item_image/" + "d".repeat(64) + ".jpg", "d".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO menu_categories (
                    id, tenant_id, menu_version_id, public_id, name, description,
                    image_asset_id, sort_order, available
                ) VALUES
                    ('a1000000-0000-4000-8000-000000000001', ?::uuid, ?::uuid,
                     'burgers', 'Бургеры', null, null, 10, true),
                    ('a2000000-0000-4000-8000-000000000001', ?::uuid, ?::uuid,
                     'rolls', 'Роллы', 'Свежие роллы', null, 10, true),
                    ('a3000000-0000-4000-8000-000000000001', ?::uuid, ?::uuid,
                     'pizza', 'Пицца', null, ?::uuid, 20, true),
                    ('a4000000-0000-4000-8000-000000000001', ?::uuid, ?::uuid,
                     'hidden', 'Скрыто', null, null, 0, false),
                    ('ad000000-0000-4000-8000-000000000001', ?::uuid,
                     '11120000-0000-4000-8000-000000000001',
                     'draft', 'Черновик', null, null, 0, true),
                    ('ae000000-0000-4000-8000-000000000001', ?::uuid,
                     '11130000-0000-4000-8000-000000000001',
                     'archived', 'Архив', null, null, 0, true),
                    ('b1000000-0000-4000-8000-000000000002', ?::uuid,
                     '22210000-0000-4000-8000-000000000002',
                     'pizza', 'Пицца', null, null, 10, true)
                """,
                TENANT_A, MENU_A,
                TENANT_A, MENU_A,
                TENANT_A, MENU_A, ASSET_CATEGORY_A,
                TENANT_A, MENU_A,
                TENANT_A, TENANT_A, TENANT_B);
        jdbcTemplate.update("""
                INSERT INTO menu_items (
                    id, tenant_id, menu_version_id, category_id, public_id, name,
                    description, price_minor, currency, image_asset_id, available, sort_order
                ) VALUES
                    ('c1000000-0000-4000-8000-000000000001', ?::uuid, ?::uuid,
                     'a1000000-0000-4000-8000-000000000001',
                     'burger-classic', 'Бургер', null, 35000, 'RUB', null, true, 5),
                    ('c2000000-0000-4000-8000-000000000001', ?::uuid, ?::uuid,
                     'a2000000-0000-4000-8000-000000000001',
                     'roll-z', 'Ролл Z', null, 25000, 'RUB', ?::uuid, true, 5),
                    ('c3000000-0000-4000-8000-000000000001', ?::uuid, ?::uuid,
                     'a2000000-0000-4000-8000-000000000001',
                     'roll-a', 'Ролл A', null, 26000, 'RUB', null, false, 5),
                    ('c4000000-0000-4000-8000-000000000001', ?::uuid, ?::uuid,
                     'a3000000-0000-4000-8000-000000000001',
                     'pizza-pepperoni', 'Пепперони Stambul', 'Описание',
                     4000000000, 'RUB', ?::uuid, true, 10),
                    ('c5000000-0000-4000-8000-000000000001', ?::uuid, ?::uuid,
                     'a4000000-0000-4000-8000-000000000001',
                     'hidden-secret', 'Скрытая позиция', null, 1, 'RUB', null, true, 0),
                    ('cd000000-0000-4000-8000-000000000001', ?::uuid,
                     '11120000-0000-4000-8000-000000000001',
                     'ad000000-0000-4000-8000-000000000001',
                     'draft-only', 'Черновик', null, 1, 'RUB', null, true, 0),
                    ('ce000000-0000-4000-8000-000000000001', ?::uuid,
                     '11130000-0000-4000-8000-000000000001',
                     'ae000000-0000-4000-8000-000000000001',
                     'archived-only', 'Архив', null, 1, 'RUB', null, true, 0),
                    ('d1000000-0000-4000-8000-000000000002', ?::uuid,
                     '22210000-0000-4000-8000-000000000002',
                     'b1000000-0000-4000-8000-000000000002',
                     'pizza-pepperoni', 'Пепперони Adamas', null,
                     59000, 'RUB', 'fb000000-0000-4000-8000-000000000001', true, 10)
                """,
                TENANT_A, MENU_A,
                TENANT_A, MENU_A, ASSET_DELETED_A,
                TENANT_A, MENU_A,
                TENANT_A, MENU_A, ASSET_ITEM_A,
                TENANT_A, MENU_A,
                TENANT_A,
                TENANT_A,
                TENANT_B);
    }
}
