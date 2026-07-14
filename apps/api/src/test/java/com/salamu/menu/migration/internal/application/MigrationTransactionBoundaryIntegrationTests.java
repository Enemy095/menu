package com.salamu.menu.migration.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.api.NotificationDestinationSpec;
import com.salamu.menu.notification.api.NotificationDestinationStatus;
import com.salamu.menu.notification.api.NotificationProvisioningService;
import com.salamu.menu.notification.api.OpaqueSecretReference;
import com.salamu.menu.notification.api.SecretReferenceReadiness;
import com.salamu.menu.restaurant.api.DefaultRestaurantSpec;
import com.salamu.menu.restaurant.api.RestaurantProvisioningService;
import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;
import com.salamu.menu.tenancy.api.SuspendedTenantSpec;
import com.salamu.menu.tenancy.api.TenantActivationCandidate;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot;
import com.salamu.menu.tenancy.api.TenantFeatureKey;
import com.salamu.menu.tenancy.api.TenantLifecycle;
import com.salamu.menu.tenancy.api.TenantProvisioningService;
import com.salamu.menu.tenancy.api.TenantProvisioningState.ConfigurationPresence;
import com.salamu.menu.tenancy.internal.application.TenantNotFoundException;
import com.salamu.menu.tenancy.internal.application.TenantResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles({"test", "legacy-import"})
@SpringBootTest
class MigrationTransactionBoundaryIntegrationTests {

    private static final String TENANT_SLUG = "transaction-boundary";
    private static final LegacyImportRunFingerprint LEDGER_FINGERPRINT =
            new LegacyImportRunFingerprint("a".repeat(64));
    private static final ProvisioningRunFingerprint MODULE_FINGERPRINT =
            new ProvisioningRunFingerprint(LEDGER_FINGERPRINT.value());

    private static final LegacyImportRunState FINAL_TX_PENDING = new LegacyImportRunState(
            LegacyImportRunStatus.APPLYING, LegacyImportRunStage.FINAL_TX_PENDING);
    private static final LegacyImportRunState DB_PUBLISHED = new LegacyImportRunState(
            LegacyImportRunStatus.APPLIED, LegacyImportRunStage.DB_PUBLISHED);
    private static final LegacyImportTenantState TENANT_FINAL_TX_PENDING = new LegacyImportTenantState(
            LegacyImportTenantStatus.STAGED, LegacyImportRunStage.FINAL_TX_PENDING);
    private static final LegacyImportTenantState TENANT_DB_PUBLISHED = new LegacyImportTenantState(
            LegacyImportTenantStatus.APPLIED, LegacyImportRunStage.DB_PUBLISHED);

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine3.21")
            .withDatabaseName("migration_transaction_boundary")
            .withUsername("migration_transaction_boundary")
            .withPassword("migration_transaction_boundary");

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
    private ApplicationContext applicationContext;

    @Autowired
    private LegacyImportLedger ledger;

    @Autowired
    private TenantProvisioningService tenantProvisioning;

    @Autowired
    private RestaurantProvisioningService restaurantProvisioning;

    @Autowired
    private NotificationProvisioningService notificationProvisioning;

    @Autowired
    private TenantResolver tenantResolver;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    legacy_import_run_assets,
                    legacy_import_run_tenants,
                    legacy_import_runs,
                    tenants
                CASCADE
                """);
    }

    @Test
    void finalPublicationUsesOneTransactionManagerAndCommitsOrRollsBackEveryModuleTogether() {
        assertSingleDatabaseTransactionBoundary();
        var acquisition = acquireSuspendedImport();

        assertThat(tenantProvisioning.inspectProvisioningState(acquisition.tenant().tenantId()))
                .get()
                .satisfies(state -> {
                    assertThat(state.lifecycle()).isEqualTo(TenantLifecycle.SUSPENDED);
                    assertThat(state.configurationPresence()).isEqualTo(ConfigurationPresence.ABSENT);
                });
        assertPubliclyInvisible();

        assertThatThrownBy(() -> publish(acquisition, true))
                .isInstanceOf(IntentionalPublicationFailure.class);

        assertThat(ledger.inspectRun(acquisition.runId())).get()
                .extracting(LegacyImportRun::state)
                .isEqualTo(FINAL_TX_PENDING);
        assertThat(ledger.inspectTenants(acquisition.runId())).singleElement()
                .extracting(LegacyImportTenantProvenance::state)
                .isEqualTo(TENANT_FINAL_TX_PENDING);
        assertThat(status()).isEqualTo("suspended");
        assertThat(tenantProvisioning.inspectProvisioningState(acquisition.tenant().tenantId()))
                .get()
                .satisfies(state -> assertThat(state.configurationPresence())
                        .isEqualTo(ConfigurationPresence.ABSENT));
        assertThat(restaurantProvisioning.inspectDefaultRestaurant(acquisition.tenant().tenantId())).isEmpty();
        assertThat(notificationProvisioning.inspectDestinations(acquisition.tenant().tenantId())).isEmpty();
        assertThat(rowCount("tenant_branding")).isZero();
        assertThat(rowCount("tenant_profiles")).isZero();
        assertThat(rowCount("tenant_features")).isZero();
        assertThat(rowCount("restaurants")).isZero();
        assertThat(rowCount("notification_destinations")).isZero();
        assertThat(rowCount("tenant_assets")).isOne();
        assertPubliclyInvisible();

        publish(acquisition, false);

        assertThat(ledger.inspectRun(acquisition.runId())).get()
                .extracting(LegacyImportRun::state)
                .isEqualTo(DB_PUBLISHED);
        assertThat(ledger.inspectTenants(acquisition.runId())).singleElement()
                .extracting(LegacyImportTenantProvenance::state)
                .isEqualTo(TENANT_DB_PUBLISHED);
        assertThat(status()).isEqualTo("active");
        assertThat(tenantProvisioning.inspectProvisioningState(acquisition.tenant().tenantId()))
                .get()
                .satisfies(state -> {
                    assertThat(state.lifecycle()).isEqualTo(TenantLifecycle.ACTIVE);
                    assertThat(state.configurationPresence()).isEqualTo(ConfigurationPresence.COMPLETE);
                });
        assertThat(restaurantProvisioning.inspectDefaultRestaurant(acquisition.tenant().tenantId())).isPresent();
        assertThat(notificationProvisioning.inspectDestinations(acquisition.tenant().tenantId())).hasSize(1);
        assertThat(rowCount("tenant_branding")).isOne();
        assertThat(rowCount("tenant_profiles")).isOne();
        assertThat(rowCount("tenant_features")).isEqualTo(TenantFeatureKey.values().length);
        assertThat(rowCount("restaurants")).isOne();
        assertThat(rowCount("notification_destinations")).isOne();
        assertThat(tenantResolver.resolve("example.test", Optional.of(TENANT_SLUG)).context().tenantId())
                .isEqualTo(acquisition.tenant().tenantId());
    }

    private void assertSingleDatabaseTransactionBoundary() {
        var dataSources = applicationContext.getBeansOfType(DataSource.class);
        var transactionManagers = applicationContext.getBeansOfType(PlatformTransactionManager.class);

        assertThat(dataSources).hasSize(1);
        assertThat(transactionManagers).hasSize(1);
        assertThat(transactions.getTransactionManager()).isSameAs(transactionManagers.values().iterator().next());
        assertThat(jdbcTemplate.getDataSource()).isSameAs(dataSources.values().iterator().next());
    }

    private Acquisition acquireSuspendedImport() {
        return transactions.execute(status -> {
            ledger.acquireTransactionLock(LEDGER_FINGERPRINT);
            var run = ledger.createRun(runDefinition());
            ledger.registerTenantPlan(run.runId(), LEDGER_FINGERPRINT, tenantPlan());

            var tenant = tenantProvisioning.provisionSuspendedSkeleton(
                    new SuspendedTenantSpec(TENANT_SLUG, "ru-RU", "RUB", "Europe/Moscow"),
                    MODULE_FINGERPRINT);
            var tenantSkeletons = new LegacyImportTenantState(
                    LegacyImportTenantStatus.SKELETON, LegacyImportRunStage.SKELETONS_SUSPENDED);
            assertThat(ledger.attachTenantAndCompareAndSet(
                            run.runId(),
                            TENANT_SLUG,
                            LegacyImportTenantState.initial(),
                            tenantSkeletons,
                            tenant.tenantId().value()))
                    .isTrue();

            var assetId = insertPreparedLogo(tenant.tenantId().value());
            advanceAcquisitionLedger(run.runId(), tenantSkeletons);
            return new Acquisition(
                    run.runId(),
                    tenant,
                    configuration(tenant, assetId),
                    new DefaultRestaurantSpec(tenant.tenantId(), TENANT_SLUG, "main", "Transaction boundary"),
                    new NotificationDestinationSpec(
                            tenant.tenantId(),
                            TENANT_SLUG,
                            NotificationChannel.WHATSAPP,
                            "manager",
                            NotificationDestinationStatus.ACTIVE,
                            new OpaqueSecretReference(
                                    "secret://test/tenants/transaction-boundary/whatsapp"),
                            SecretReferenceReadiness.READY));
        });
    }

    private void advanceAcquisitionLedger(UUID runId, LegacyImportTenantState tenantSkeletons) {
        var tenantAssetsPending = new LegacyImportTenantState(
                LegacyImportTenantStatus.SKELETON, LegacyImportRunStage.ASSETS_PENDING);
        var tenantAssetsStaged = new LegacyImportTenantState(
                LegacyImportTenantStatus.STAGED, LegacyImportRunStage.ASSETS_STAGED);
        assertThat(ledger.compareAndSetTenant(runId, TENANT_SLUG, tenantSkeletons, tenantAssetsPending)).isTrue();
        assertThat(ledger.compareAndSetTenant(runId, TENANT_SLUG, tenantAssetsPending, tenantAssetsStaged)).isTrue();
        assertThat(ledger.compareAndSetTenant(runId, TENANT_SLUG, tenantAssetsStaged, TENANT_FINAL_TX_PENDING))
                .isTrue();

        var skeletons = new LegacyImportRunState(
                LegacyImportRunStatus.STAGING, LegacyImportRunStage.SKELETONS_SUSPENDED);
        var assetsPending = new LegacyImportRunState(
                LegacyImportRunStatus.STAGING, LegacyImportRunStage.ASSETS_PENDING);
        var assetsStaged = new LegacyImportRunState(
                LegacyImportRunStatus.STAGING, LegacyImportRunStage.ASSETS_STAGED);
        assertThat(ledger.compareAndSetRun(runId, LegacyImportRunState.initial(), skeletons, null)).isTrue();
        assertThat(ledger.compareAndSetRun(runId, skeletons, assetsPending, null)).isTrue();
        assertThat(ledger.compareAndSetRun(runId, assetsPending, assetsStaged, null)).isTrue();
        assertThat(ledger.compareAndSetRun(runId, assetsStaged, FINAL_TX_PENDING, null)).isTrue();
    }

    private void publish(Acquisition acquisition, boolean failAfterAllWrites) {
        transactions.executeWithoutResult(status -> {
            ledger.acquireTransactionLock(LEDGER_FINGERPRINT);
            assertThat(ledger.lockRun(acquisition.runId())).get()
                    .extracting(LegacyImportRun::state)
                    .isEqualTo(FINAL_TX_PENDING);
            assertThat(ledger.lockTenants(acquisition.runId())).singleElement()
                    .extracting(LegacyImportTenantProvenance::state)
                    .isEqualTo(TENANT_FINAL_TX_PENDING);

            var configurationHash = tenantProvisioning.writeTenantConfiguration(
                    acquisition.configuration(), MODULE_FINGERPRINT);
            var restaurant = restaurantProvisioning.writeDefaultRestaurant(
                    acquisition.restaurant(), MODULE_FINGERPRINT);
            var destination = notificationProvisioning.writeDestination(
                    acquisition.destination(), MODULE_FINGERPRINT);

            assertThat(tenantProvisioning.verifyPreparedConfiguration(
                            acquisition.configuration(), MODULE_FINGERPRINT))
                    .isEqualTo(configurationHash);
            assertThat(restaurantProvisioning.verifyPreparedDefaultRestaurant(
                            acquisition.restaurant(), MODULE_FINGERPRINT))
                    .isEqualTo(restaurant.semanticHash());
            assertThat(notificationProvisioning.verifyPreparedDestination(
                            acquisition.destination(), MODULE_FINGERPRINT))
                    .isEqualTo(destination.semanticHash());

            tenantProvisioning.activateCompletedBatch(
                    List.of(new TenantActivationCandidate(
                            acquisition.tenant().tenantId(), TENANT_SLUG, configurationHash)),
                    MODULE_FINGERPRINT);
            assertThat(ledger.compareAndSetTenant(
                            acquisition.runId(), TENANT_SLUG, TENANT_FINAL_TX_PENDING, TENANT_DB_PUBLISHED))
                    .isTrue();
            assertThat(ledger.compareAndSetRun(
                            acquisition.runId(), FINAL_TX_PENDING, DB_PUBLISHED, null))
                    .isTrue();

            if (failAfterAllWrites) {
                throw new IntentionalPublicationFailure();
            }
        });
    }

    private TenantAssetId insertPreparedLogo(UUID tenantId) {
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

    private void assertPubliclyInvisible() {
        assertThatThrownBy(() -> tenantResolver.resolve("example.test", Optional.of(TENANT_SLUG)))
                .isInstanceOf(TenantNotFoundException.class);
    }

    private int rowCount(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String status() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM tenants WHERE slug = ?", String.class, TENANT_SLUG);
    }

    private static LegacyImportRunDefinition runDefinition() {
        return new LegacyImportRunDefinition(
                "transaction-boundary-test",
                "transaction-boundary-v1",
                1,
                "test",
                LEDGER_FINGERPRINT,
                hash("1"),
                hash("2"),
                hash("3"),
                hash("4"));
    }

    private static LegacyImportTenantPlan tenantPlan() {
        return new LegacyImportTenantPlan(
                TENANT_SLUG,
                1,
                hash("5"),
                hash("6"),
                hash("7"),
                hash("8"),
                hash("9"),
                hash("c"));
    }

    private static LegacyImportSha256 hash(String digit) {
        return new LegacyImportSha256(digit.repeat(64));
    }

    private static TenantConfigurationSnapshot configuration(
            com.salamu.menu.tenancy.api.TenantProvisioningState tenant,
            TenantAssetId logoAssetId) {
        return new TenantConfigurationSnapshot(
                tenant.tenantId(),
                tenant.identity(),
                new TenantConfigurationSnapshot.Branding(
                        "Transaction boundary",
                        "restaurant",
                        logoAssetId,
                        null,
                        new TenantConfigurationSnapshot.DesignTokens(
                                "#112233",
                                "#223344",
                                "#334455",
                                "#445566",
                                "#556677",
                                "#667788",
                                "#778899",
                                "12px",
                                "sans-serif"),
                        List.of(new TenantConfigurationSnapshot.LocalizedText(
                                "ru-RU",
                                "Transaction boundary",
                                "Transaction",
                                "Restaurant",
                                "Cuisine",
                                "Reliable publication",
                                "Integration test tenant",
                                "Menu",
                                "Cart",
                                "Order type")),
                        new TenantConfigurationSnapshot.Hero(
                                null,
                                null,
                                "Menu",
                                "Transaction boundary",
                                "All modules commit together",
                                "View menu",
                                List.of()),
                        new TenantConfigurationSnapshot.SeoMetadata(
                                "Transaction boundary",
                                "Integration test tenant",
                                "https://example.test/" + TENANT_SLUG,
                                null)),
                new TenantConfigurationSnapshot.Profile(
                        "+79990000000",
                        "Call restaurant",
                        null,
                        null,
                        List.of(),
                        "Daily 10:00-23:00",
                        null,
                        null,
                        List.of()),
                features());
    }

    private static List<TenantConfigurationSnapshot.Feature> features() {
        var features = new ArrayList<TenantConfigurationSnapshot.Feature>();
        for (var key : TenantFeatureKey.values()) {
            if (key == TenantFeatureKey.ORDERS) {
                features.add(new TenantConfigurationSnapshot.Feature(
                        key,
                        true,
                        null,
                        false,
                        new TenantConfigurationSnapshot.OrderLimits(
                                99, 200, 80, 32, 240, 500, null, null, 15)));
            } else if (key == TenantFeatureKey.PREORDER) {
                features.add(new TenantConfigurationSnapshot.Feature(key, true, "Preorder", true, null));
            } else if (key == TenantFeatureKey.DELIVERY) {
                features.add(new TenantConfigurationSnapshot.Feature(key, true, "Delivery", false, null));
            } else if (key == TenantFeatureKey.PICKUP) {
                features.add(new TenantConfigurationSnapshot.Feature(key, true, "Pickup", false, null));
            } else {
                features.add(new TenantConfigurationSnapshot.Feature(key, false, null, false, null));
            }
        }
        return List.copyOf(features);
    }

    private record Acquisition(
            UUID runId,
            com.salamu.menu.tenancy.api.TenantProvisioningState tenant,
            TenantConfigurationSnapshot configuration,
            DefaultRestaurantSpec restaurant,
            NotificationDestinationSpec destination) {
    }

    private static final class IntentionalPublicationFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
