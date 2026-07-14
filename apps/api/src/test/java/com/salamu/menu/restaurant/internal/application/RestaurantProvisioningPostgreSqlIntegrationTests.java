package com.salamu.menu.restaurant.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.salamu.menu.restaurant.api.DefaultRestaurantCleanupCandidate;
import com.salamu.menu.restaurant.api.DefaultRestaurantSpec;
import com.salamu.menu.restaurant.api.RestaurantProvisioningException;
import com.salamu.menu.restaurant.api.RestaurantProvisioningService;
import com.salamu.menu.restaurant.api.RestaurantSemanticHash;
import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;
import com.salamu.menu.tenancy.api.SuspendedTenantSpec;
import com.salamu.menu.tenancy.api.TenantProvisioningService;
import com.salamu.menu.tenancy.api.TenantProvisioningState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles({"test", "legacy-import"})
@SpringBootTest
class RestaurantProvisioningPostgreSqlIntegrationTests {

    private static final ProvisioningRunFingerprint RUN =
            new ProvisioningRunFingerprint("a".repeat(64));
    private static final UUID RUN_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine3.21")
            .withDatabaseName("restaurant_provisioning")
            .withUsername("restaurant_provisioning")
            .withPassword("restaurant_provisioning");

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
    private RestaurantProvisioningService restaurantProvisioning;

    @Autowired
    private TenantProvisioningService tenantProvisioning;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE tenants CASCADE");
    }

    @Test
    void writerAndVerifierAreMandatoryIdempotentAndFailClosedOnMismatch() {
        var tenant = skeleton("tenant-a");
        var expected = spec(tenant, "Restaurant A");

        assertThatThrownBy(() -> restaurantProvisioning.writeDefaultRestaurant(expected, RUN))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> restaurantProvisioning.verifyPreparedDefaultRestaurant(expected, RUN))
                .isInstanceOf(IllegalTransactionStateException.class);

        var first = transactions.execute(status -> {
            var written = restaurantProvisioning.writeDefaultRestaurant(expected, RUN);
            assertThat(restaurantProvisioning.verifyPreparedDefaultRestaurant(expected, RUN))
                    .isEqualTo(written.semanticHash());
            return written;
        });
        var rerun = transactions.execute(status ->
                restaurantProvisioning.writeDefaultRestaurant(expected, RUN));

        assertThat(rerun).isEqualTo(first);
        assertThat(restaurantProvisioning.inspectDefaultRestaurant(tenant.tenantId())).contains(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM restaurants WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId().value())).isEqualTo(1);

        var mismatch = spec(tenant, "Different Restaurant");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                restaurantProvisioning.writeDefaultRestaurant(mismatch, RUN)))
                .isInstanceOf(RestaurantProvisioningException.class)
                .extracting(error -> ((RestaurantProvisioningException) error).code())
                .isEqualTo(RestaurantProvisioningException.Code.RESTAURANT_CONFLICT);
        assertThat(restaurantProvisioning.inspectDefaultRestaurant(tenant.tenantId())).contains(first);
    }

    @Test
    void sameRestaurantSlugIsTenantScopedAndWrongTenantIdentityIsRejected() {
        var tenantA = skeleton("tenant-a");
        var tenantB = skeleton("tenant-b");
        var restaurantA = transactions.execute(status ->
                restaurantProvisioning.writeDefaultRestaurant(spec(tenantA, "Restaurant"), RUN));
        var restaurantB = transactions.execute(status ->
                restaurantProvisioning.writeDefaultRestaurant(spec(tenantB, "Restaurant"), RUN));

        assertThat(restaurantA.restaurantSlug()).isEqualTo(restaurantB.restaurantSlug());
        assertThat(restaurantA.restaurantId()).isNotEqualTo(restaurantB.restaurantId());
        assertThat(restaurantProvisioning.inspectDefaultRestaurant(tenantA.tenantId()))
                .contains(restaurantA);
        assertThat(restaurantProvisioning.inspectDefaultRestaurant(tenantB.tenantId()))
                .contains(restaurantB);

        var wrongIdentity = new DefaultRestaurantSpec(
                tenantA.tenantId(), "tenant-b", "main", "Restaurant");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                restaurantProvisioning.writeDefaultRestaurant(wrongIdentity, RUN)))
                .isInstanceOf(RestaurantProvisioningException.class)
                .extracting(error -> ((RestaurantProvisioningException) error).code())
                .isEqualTo(RestaurantProvisioningException.Code.TENANT_STATE_CONFLICT);
    }

    @Test
    void inspectionSupportsActiveTenantButCleanupRequiresExactSuspendedProof() {
        var tenant = skeleton("tenant-a");
        var snapshot = transactions.execute(status ->
                restaurantProvisioning.writeDefaultRestaurant(spec(tenant, "Restaurant"), RUN));
        var wrongHash = new DefaultRestaurantCleanupCandidate(
                tenant.tenantId(),
                "tenant-a",
                snapshot.restaurantId(),
                snapshot.restaurantSlug(),
                new RestaurantSemanticHash(RestaurantSemanticHash.CURRENT_SCHEMA_VERSION, "b".repeat(64)));

        assertThatThrownBy(() -> restaurantProvisioning.cleanupDefaultRestaurant(RUN_ID, RUN, wrongHash))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                restaurantProvisioning.cleanupDefaultRestaurant(RUN_ID, RUN, wrongHash)))
                .isInstanceOf(RestaurantProvisioningException.class);

        jdbcTemplate.update("UPDATE tenants SET status = 'active' WHERE id = ?", tenant.tenantId().value());
        assertThat(restaurantProvisioning.inspectDefaultRestaurant(tenant.tenantId())).contains(snapshot);
        var candidate = cleanup(tenant, snapshot);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                restaurantProvisioning.cleanupDefaultRestaurant(RUN_ID, RUN, candidate)))
                .isInstanceOf(RestaurantProvisioningException.class)
                .extracting(error -> ((RestaurantProvisioningException) error).code())
                .isEqualTo(RestaurantProvisioningException.Code.TENANT_STATE_CONFLICT);

        jdbcTemplate.update("UPDATE tenants SET status = 'suspended' WHERE id = ?", tenant.tenantId().value());
        transactions.executeWithoutResult(status -> {
            restaurantProvisioning.cleanupDefaultRestaurant(RUN_ID, RUN, candidate);
            restaurantProvisioning.cleanupDefaultRestaurant(RUN_ID, RUN, candidate);
        });
        assertThat(restaurantProvisioning.inspectDefaultRestaurant(tenant.tenantId())).isEmpty();
    }

    private TenantProvisioningState skeleton(String slug) {
        return transactions.execute(status -> tenantProvisioning.provisionSuspendedSkeleton(
                new SuspendedTenantSpec(slug, "ru-RU", "RUB", "Europe/Moscow"), RUN));
    }

    private static DefaultRestaurantSpec spec(TenantProvisioningState tenant, String displayName) {
        return new DefaultRestaurantSpec(
                tenant.tenantId(), tenant.identity().slug(), "main", displayName);
    }

    private static DefaultRestaurantCleanupCandidate cleanup(
            TenantProvisioningState tenant,
            com.salamu.menu.restaurant.api.DefaultRestaurantSnapshot snapshot) {
        return new DefaultRestaurantCleanupCandidate(
                tenant.tenantId(),
                tenant.identity().slug(),
                snapshot.restaurantId(),
                snapshot.restaurantSlug(),
                snapshot.semanticHash());
    }
}
