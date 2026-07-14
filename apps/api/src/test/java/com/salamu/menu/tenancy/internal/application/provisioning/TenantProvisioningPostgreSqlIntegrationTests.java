package com.salamu.menu.tenancy.internal.application.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;
import com.salamu.menu.tenancy.api.SuspendedTenantSpec;
import com.salamu.menu.tenancy.api.TenantActivationCandidate;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantCleanupCandidate;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot;
import com.salamu.menu.tenancy.api.TenantLifecycle;
import com.salamu.menu.tenancy.api.TenantProvisioningException;
import com.salamu.menu.tenancy.api.TenantProvisioningService;
import com.salamu.menu.tenancy.api.TenantProvisioningState.ConfigurationPresence;
import com.salamu.menu.tenancy.api.TenantSemanticHash;
import com.salamu.menu.tenancy.internal.application.TenantNotFoundException;
import com.salamu.menu.tenancy.internal.application.TenantResolver;

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
class TenantProvisioningPostgreSqlIntegrationTests {

    private static final ProvisioningRunFingerprint RUN =
            new ProvisioningRunFingerprint("a".repeat(64));
    private static final UUID RUN_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine3.21")
            .withDatabaseName("tenant_provisioning")
            .withUsername("tenant_provisioning")
            .withPassword("tenant_provisioning");

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
    private TenantProvisioningService provisioningService;

    @Autowired
    private TenantResolver tenantResolver;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE tenants CASCADE");
    }

    @Test
    void acquisitionRequiresOuterTransactionAndSuspendedTenantRemainsPubliclyInvisible() {
        var identity = identity("tenant-a");
        assertThatThrownBy(() -> provisioningService.provisionSuspendedSkeleton(identity, RUN))
                .isInstanceOf(IllegalTransactionStateException.class);

        var first = transactions.execute(status ->
                provisioningService.provisionSuspendedSkeleton(identity, RUN));
        var second = transactions.execute(status ->
                provisioningService.provisionSuspendedSkeleton(identity, RUN));

        assertThat(second).isEqualTo(first);
        assertThat(first.lifecycle()).isEqualTo(TenantLifecycle.SUSPENDED);
        assertThat(first.configurationPresence()).isEqualTo(ConfigurationPresence.ABSENT);
        assertThat(provisioningService.inspectProvisioningState("tenant-a"))
                .contains(first);
        assertThat(provisioningService.inspectProvisioningState(first.tenantId()))
                .contains(first);
        assertThat(provisioningService.inspectProvisioningState(
                new com.salamu.menu.tenancy.api.TenantId(UUID.randomUUID())))
                .isEmpty();
        assertThatThrownBy(() -> tenantResolver.resolve("example.test", Optional.of("tenant-a")))
                .isInstanceOf(TenantNotFoundException.class);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                provisioningService.provisionSuspendedSkeleton(
                        new SuspendedTenantSpec("tenant-a", "en", "USD", "UTC"), RUN)))
                .isInstanceOf(TenantProvisioningException.class);
    }

    @Test
    void configurationWriterIsMandatoryIdempotentAndRejectsSemanticMismatch() {
        var tenant = skeleton("tenant-a");
        var logo = insertLogo(tenant.tenantId().value());
        var snapshot = TenantProvisioningTestFixtures.configuration(tenant.tenantId(), tenant.identity(), logo);

        assertThatThrownBy(() -> provisioningService.writeTenantConfiguration(snapshot, RUN))
                .isInstanceOf(IllegalTransactionStateException.class);

        var firstHash = transactions.execute(status -> {
            var written = provisioningService.writeTenantConfiguration(snapshot, RUN);
            assertThat(provisioningService.verifyPreparedConfiguration(snapshot, RUN)).isEqualTo(written);
            return written;
        });
        var secondHash = transactions.execute(status ->
                provisioningService.writeTenantConfiguration(snapshot, RUN));
        assertThat(secondHash).isEqualTo(firstHash);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tenant_features WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId().value())).isEqualTo(13);

        var mismatch = TenantProvisioningTestFixtures.configuration(
                tenant.tenantId(), tenant.identity(), logo, "Different restaurant");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                provisioningService.writeTenantConfiguration(mismatch, RUN)))
                .isInstanceOf(TenantProvisioningException.class)
                .extracting(error -> ((TenantProvisioningException) error).code())
                .isEqualTo(TenantProvisioningException.Code.CONFIGURATION_CONFLICT);

        var candidate = new TenantActivationCandidate(tenant.tenantId(), "tenant-a", firstHash);
        var cleanupCandidate = new TenantCleanupCandidate(tenant.tenantId(), "tenant-a", firstHash);
        assertThatThrownBy(() -> provisioningService.verifyPreparedConfiguration(snapshot, RUN))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> provisioningService.activateCompletedBatch(List.of(candidate), RUN))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> provisioningService.cleanupTenantConfiguration(
                RUN_ID, RUN, List.of(cleanupCandidate)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> provisioningService.cleanupSuspendedRun(
                RUN_ID, RUN, List.of(cleanupCandidate)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void activatesTwoTenantsInStableOrderAndRejectsMixedBatchWithoutPartialUpdate() {
        var tenantB = prepared("tenant-b");
        var tenantA = prepared("tenant-a");

        transactions.executeWithoutResult(status -> provisioningService.activateCompletedBatch(
                List.of(
                        new TenantActivationCandidate(
                                tenantB.snapshot().tenantId(), "tenant-b", tenantB.hash()),
                        new TenantActivationCandidate(
                                tenantA.snapshot().tenantId(), "tenant-a", tenantA.hash())),
                RUN));

        assertThat(status("tenant-a")).isEqualTo("active");
        assertThat(status("tenant-b")).isEqualTo("active");
        assertThat(provisioningService.inspectProvisioningState("tenant-a").orElseThrow().lifecycle())
                .isEqualTo(TenantLifecycle.ACTIVE);
        assertThat(tenantResolver.resolve("example.test", Optional.of("tenant-a")).context().slug())
                .isEqualTo("tenant-a");

        var tenantC = prepared("tenant-c");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                provisioningService.activateCompletedBatch(
                        List.of(
                                new TenantActivationCandidate(
                                        tenantA.snapshot().tenantId(), "tenant-a", tenantA.hash()),
                                new TenantActivationCandidate(
                                        tenantC.snapshot().tenantId(), "tenant-c", tenantC.hash())),
                        RUN)))
                .isInstanceOf(TenantProvisioningException.class);
        assertThat(status("tenant-c")).isEqualTo("suspended");
    }

    @Test
    void cleanupRequiresExactSuspendedStateAndAssetRegistryRemovalBetweenPhases() {
        var prepared = prepared("tenant-a");
        var candidate = new TenantCleanupCandidate(
                prepared.snapshot().tenantId(), "tenant-a", prepared.hash());

        var wrongHash = new TenantCleanupCandidate(
                prepared.snapshot().tenantId(),
                "tenant-a",
                new TenantSemanticHash(TenantSemanticHash.CURRENT_SCHEMA_VERSION, "c".repeat(64)));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                provisioningService.cleanupTenantConfiguration(RUN_ID, RUN, List.of(wrongHash))))
                .isInstanceOf(TenantProvisioningException.class);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            provisioningService.cleanupTenantConfiguration(RUN_ID, RUN, List.of(candidate));
            provisioningService.cleanupSuspendedRun(RUN_ID, RUN, List.of(candidate));
        })).isInstanceOf(TenantProvisioningException.class);
        assertThat(status("tenant-a")).isEqualTo("suspended");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tenant_branding WHERE tenant_id = ?",
                Integer.class,
                prepared.snapshot().tenantId().value())).isEqualTo(1);

        transactions.executeWithoutResult(status -> {
            provisioningService.cleanupTenantConfiguration(RUN_ID, RUN, List.of(candidate));
            jdbcTemplate.update(
                    "DELETE FROM tenant_assets WHERE tenant_id = ?",
                    prepared.snapshot().tenantId().value());
            provisioningService.cleanupSuspendedRun(RUN_ID, RUN, List.of(candidate));
        });
        assertThat(provisioningService.inspectProvisioningState("tenant-a")).isEmpty();
    }

    @Test
    void cleanupOfEmptySkeletonIsIdempotentOnlyAtOrchestratorLedgerLevel() {
        var tenant = skeleton("tenant-a");
        var candidate = new TenantCleanupCandidate(tenant.tenantId(), "tenant-a", null);
        transactions.executeWithoutResult(status -> {
            provisioningService.cleanupTenantConfiguration(RUN_ID, RUN, List.of(candidate));
            provisioningService.cleanupSuspendedRun(RUN_ID, RUN, List.of(candidate));
        });
        assertThat(provisioningService.inspectProvisioningState("tenant-a")).isEmpty();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                provisioningService.cleanupSuspendedRun(RUN_ID, RUN, List.of(candidate))))
                .isInstanceOf(TenantProvisioningException.class);
    }

    @Test
    void tenantIdInspectionAllowsActiveAndSuspendedButRejectsDeletedWithoutWrites() {
        var suspended = skeleton("tenant-a");
        var active = prepared("tenant-b");
        transactions.executeWithoutResult(status -> provisioningService.activateCompletedBatch(
                List.of(new TenantActivationCandidate(
                        active.snapshot().tenantId(), "tenant-b", active.hash())),
                RUN));
        var deleted = skeleton("tenant-c");
        jdbcTemplate.update(
                "UPDATE tenants SET status = 'deleted' WHERE id = ?",
                deleted.tenantId().value());
        var updatedBefore = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM tenants WHERE id = ?",
                java.time.OffsetDateTime.class,
                suspended.tenantId().value());

        assertThat(provisioningService.inspectProvisioningState(suspended.tenantId()).orElseThrow().lifecycle())
                .isEqualTo(TenantLifecycle.SUSPENDED);
        assertThat(provisioningService.inspectProvisioningState(active.snapshot().tenantId()).orElseThrow().lifecycle())
                .isEqualTo(TenantLifecycle.ACTIVE);
        assertThatThrownBy(() -> provisioningService.inspectProvisioningState(deleted.tenantId()))
                .isInstanceOf(TenantProvisioningException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT updated_at FROM tenants WHERE id = ?",
                java.time.OffsetDateTime.class,
                suspended.tenantId().value())).isEqualTo(updatedBefore);
    }

    private PreparedTenant prepared(String slug) {
        var tenant = skeleton(slug);
        var logo = insertLogo(tenant.tenantId().value());
        var snapshot = TenantProvisioningTestFixtures.configuration(tenant.tenantId(), tenant.identity(), logo);
        var hash = transactions.execute(status ->
                provisioningService.writeTenantConfiguration(snapshot, RUN));
        return new PreparedTenant(snapshot, hash);
    }

    private com.salamu.menu.tenancy.api.TenantProvisioningState skeleton(String slug) {
        return transactions.execute(status ->
                provisioningService.provisionSuspendedSkeleton(identity(slug), RUN));
    }

    private TenantAssetId insertLogo(UUID tenantId) {
        var assetId = UUID.randomUUID();
        var sha256 = "b".repeat(64);
        jdbcTemplate.update("""
                INSERT INTO tenant_assets (
                    id, tenant_id, object_key, asset_type, content_type, size_bytes, sha256, status
                ) VALUES (?, ?, ?, 'logo', 'image/png', 10, ?, 'active')
                """,
                assetId,
                tenantId,
                "tenants/" + tenantId + "/logo/" + sha256 + ".png",
                sha256);
        return new TenantAssetId(assetId);
    }

    private String status(String slug) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM tenants WHERE slug = ?", String.class, slug);
    }

    private static SuspendedTenantSpec identity(String slug) {
        return new SuspendedTenantSpec(slug, "ru-RU", "RUB", "Europe/Moscow");
    }

    private record PreparedTenant(
            TenantConfigurationSnapshot snapshot,
            com.salamu.menu.tenancy.api.TenantSemanticHash hash) {
    }
}
