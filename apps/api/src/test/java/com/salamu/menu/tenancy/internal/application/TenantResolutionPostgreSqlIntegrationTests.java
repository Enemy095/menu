package com.salamu.menu.tenancy.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.Filter;

import com.salamu.menu.tenancy.api.TenantResolution;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class TenantResolutionPostgreSqlIntegrationTests {

    private static final UUID TENANT_A = UUID.fromString("10000000-0000-4000-8000-000000000001");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine3.21")
            .withDatabaseName("tenant_resolution")
            .withUsername("tenant_resolution")
            .withPassword("tenant_resolution");

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
    private TenantRegistry tenantRegistry;

    @Autowired
    private TenantResolver tenantResolver;

    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void insertRegistryFixtures() {
        var filters = new java.util.ArrayList<>(applicationContext.getBeansOfType(Filter.class).values());
        AnnotationAwareOrderComparator.sort(filters);
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .addFilters(filters.toArray(Filter[]::new))
                .build();

        jdbcTemplate.update("""
                INSERT INTO tenants (id, slug, status, locale, currency, timezone)
                VALUES
                    ('10000000-0000-4000-8000-000000000001', 'stambul', 'active',
                     'ru-RU', 'RUB', 'Europe/Moscow'),
                    ('20000000-0000-4000-8000-000000000002', 'adamas', 'suspended',
                     'ru-RU', 'RUB', 'Europe/Moscow')
                """);
        jdbcTemplate.update("""
                INSERT INTO tenant_domains (
                    id, tenant_id, hostname, domain_type, status, canonical, verified_at
                ) VALUES
                    ('11000000-0000-4000-8000-000000000001',
                     '10000000-0000-4000-8000-000000000001',
                     'stambul.example.test', 'platform_subdomain', 'active', true, now()),
                    ('12000000-0000-4000-8000-000000000001',
                     '10000000-0000-4000-8000-000000000001',
                     'custom-restaurant.example', 'custom', 'active', false, now()),
                    ('13000000-0000-4000-8000-000000000001',
                     '10000000-0000-4000-8000-000000000001',
                     'pending.example.test', 'custom', 'pending', false, null),
                    ('14000000-0000-4000-8000-000000000001',
                     '10000000-0000-4000-8000-000000000001',
                     'disabled.example.test', 'custom', 'disabled', false, now()),
                    ('21000000-0000-4000-8000-000000000002',
                     '20000000-0000-4000-8000-000000000002',
                     'inactive-tenant.example.test', 'custom', 'active', true, now())
                """);
    }

    @Test
    void repositoryReturnsOnlyVerifiedDomainOfAnActiveTenant() {
        assertThat(tenantRegistry.findActiveByHostname("custom-restaurant.example"))
                .get()
                .satisfies(tenant -> {
                    assertThat(tenant.tenantId().value()).isEqualTo(TENANT_A);
                    assertThat(tenant.slug()).isEqualTo("stambul");
                });
        assertThat(tenantRegistry.findActiveByHostname("pending.example.test")).isEmpty();
        assertThat(tenantRegistry.findActiveByHostname("disabled.example.test")).isEmpty();
        assertThat(tenantRegistry.findActiveByHostname("inactive-tenant.example.test")).isEmpty();
        assertThat(tenantRegistry.findActiveBySlug("adamas")).isEmpty();
    }

    @Test
    void resolvesDatabaseBackedPlatformSubdomainCustomDomainPlatformPathAndLocalPath() {
        var subdomain = tenantResolver.resolve("stambul.example.test", Optional.of("stambul"));
        var custom = tenantResolver.resolve("custom-restaurant.example", Optional.empty());
        var platform = tenantResolver.resolve("example.test", Optional.of("stambul"));
        var local = tenantResolver.resolve("localhost", Optional.of("stambul"));

        assertResolution(subdomain, TenantResolution.Source.DOMAIN);
        assertResolution(custom, TenantResolution.Source.DOMAIN);
        assertResolution(platform, TenantResolution.Source.PLATFORM_PATH);
        assertResolution(local, TenantResolution.Source.LOCAL_PATH);
    }

    @Test
    void rejectsMismatchUnknownDomainAndInactiveTenant() {
        assertThatThrownBy(() -> tenantResolver.resolve(
                "stambul.example.test",
                Optional.of("adamas")))
                .isInstanceOf(TenantNotFoundException.class);
        assertThatThrownBy(() -> tenantResolver.resolve(
                "unknown.example.test",
                Optional.of("stambul")))
                .isInstanceOf(TenantNotFoundException.class);
        assertThatThrownBy(() -> tenantResolver.resolve(
                "inactive-tenant.example.test",
                Optional.of("adamas")))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void unknownTenantUsesTheCommonErrorEnvelopeWithoutLeakingInternalId() throws Exception {
        mockMvc.perform(get("/api/v1/public/missing/menu")
                        .header("Host", "example.test"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    private static void assertResolution(
            TenantResolution resolution,
            TenantResolution.Source expectedSource) {
        assertThat(resolution.context().tenantId().value()).isEqualTo(TENANT_A);
        assertThat(resolution.context().slug()).isEqualTo("stambul");
        assertThat(resolution.source()).isEqualTo(expectedSource);
    }
}
