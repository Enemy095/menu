package com.salamu.menu.migration.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.salamu.menu.migration.internal.application.LegacyImportAssetPlan;
import com.salamu.menu.migration.internal.application.LegacyImportAssetState;
import com.salamu.menu.migration.internal.application.LegacyImportAssetStatus;
import com.salamu.menu.migration.internal.application.LegacyImportLedger;
import com.salamu.menu.migration.internal.application.LegacyImportRunDefinition;
import com.salamu.menu.migration.internal.application.LegacyImportRunFingerprint;
import com.salamu.menu.migration.internal.application.LegacyImportRunStage;
import com.salamu.menu.migration.internal.application.LegacyImportRunState;
import com.salamu.menu.migration.internal.application.LegacyImportRunStatus;
import com.salamu.menu.migration.internal.application.LegacyImportSha256;
import com.salamu.menu.migration.internal.application.LegacyImportTenantPlan;
import com.salamu.menu.migration.internal.application.LegacyImportTenantState;
import com.salamu.menu.migration.internal.application.LegacyImportTenantStatus;
import com.salamu.menu.migration.internal.application.LegacyImportUniqueConflictException;

class LegacyImportLedgerPostgreSqlIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine3.21")
            .withDatabaseName("legacy_import_ledger")
            .withUsername("legacy_import")
            .withPassword("legacy_import");
    private static final String SCHEMA = "legacy_import_ledger";

    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static TransactionTemplate transactions;
    private static AnnotationConfigApplicationContext context;
    private static LegacyImportLedger ledger;

    private java.util.concurrent.ExecutorService executor;

    @BeforeAll
    static void startPostgreSqlAndMigrate() {
        POSTGRES.start();
        flyway(SCHEMA, null).migrate();

        var configuredDataSource = new DriverManagerDataSource();
        var separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        configuredDataSource.setUrl(POSTGRES.getJdbcUrl() + separator + "currentSchema=" + SCHEMA);
        configuredDataSource.setUsername(POSTGRES.getUsername());
        configuredDataSource.setPassword(POSTGRES.getPassword());
        dataSource = configuredDataSource;
        jdbcTemplate = new JdbcTemplate(dataSource);
        var transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);

        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("legacy-import");
        context.register(TransactionConfiguration.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(JdbcTemplate.class, () -> jdbcTemplate);
        context.registerBean(PlatformTransactionManager.class, () -> transactionManager);
        context.registerBean(JdbcLegacyImportLedger.class, () -> new JdbcLegacyImportLedger(jdbcTemplate));
        context.refresh();
        ledger = context.getBean(LegacyImportLedger.class);
    }

    @AfterAll
    static void stopPostgreSql() {
        if (context != null) {
            context.close();
        }
        POSTGRES.stop();
    }

    @BeforeEach
    void resetSchema() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    legacy_import_run_assets,
                    legacy_import_run_tenants,
                    legacy_import_runs,
                    tenant_assets,
                    tenants
                CASCADE
                """);
    }

    @AfterEach
    void stopExecutor() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void migratesFreshAndUpgradesV4WithoutChangingAppliedChecksums() throws SQLException {
        var freshSchema = "legacy_import_fresh";
        var upgradeSchema = "legacy_import_upgrade";

        flyway(freshSchema, null).migrate();
        assertThat(currentVersion(freshSchema)).isEqualTo("6");
        assertThat(tables(freshSchema)).contains(
                "legacy_import_runs",
                "legacy_import_run_tenants",
                "legacy_import_run_assets");

        var v4 = flyway(upgradeSchema, MigrationVersion.fromVersion("4"));
        v4.migrate();
        var checksumsBefore = migrationChecksums(upgradeSchema);
        assertThat(currentVersion(upgradeSchema)).isEqualTo("4");

        var upgraded = flyway(upgradeSchema, null);
        upgraded.migrate();
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
        assertThat(currentVersion(upgradeSchema)).isEqualTo("6");
        assertThat(migrationChecksums(upgradeSchema)).containsAllEntriesOf(checksumsBefore);
    }

    @Test
    void requiresExternalTransactionAndUsesCompareAndSet() {
        assertThatThrownBy(() -> ledger.createRun(definition("a", "a")))
                .isInstanceOf(IllegalTransactionStateException.class);

        var run = inTransaction(() -> {
            var definition = definition("a", "a");
            ledger.acquireTransactionLock(definition.runFingerprint());
            return ledger.createRun(definition);
        });
        var skeletons = runState(LegacyImportRunStatus.STAGING, LegacyImportRunStage.SKELETONS_SUSPENDED);

        assertThat(inTransaction(() -> ledger.compareAndSetRun(
                        run.runId(), LegacyImportRunState.initial(), skeletons, null)))
                .isTrue();
        assertThat(inTransaction(() -> ledger.compareAndSetRun(
                        run.runId(), LegacyImportRunState.initial(), skeletons, null)))
                .isFalse();
        assertThat(ledger.inspectRun(run.runId())).get().extracting(value -> value.state()).isEqualTo(skeletons);
    }

    @Test
    void transactionAdvisoryLockSerializesAndReleasesAtCommit() throws Exception {
        var fingerprint = fingerprint("1");
        var firstHasLock = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);

        var first = executor.submit(() -> transactions.executeWithoutResult(status -> {
            ledger.acquireTransactionLock(fingerprint);
            firstHasLock.countDown();
            await(releaseFirst);
        }));
        assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).isTrue();

        var second = executor.submit(() -> transactions.executeWithoutResult(status ->
                ledger.acquireTransactionLock(fingerprint)));
        assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        releaseFirst.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

        var third = executor.submit(() -> transactions.executeWithoutResult(status ->
                ledger.acquireTransactionLock(fingerprint)));
        third.get(2, TimeUnit.SECONDS);
    }

    @Test
    void rowLockBlocksConcurrentCasUntilOwningTransactionCommits() throws Exception {
        var run = inTransaction(() -> ledger.createRun(definition("a", "a")));
        var rowLocked = new CountDownLatch(1);
        var releaseRow = new CountDownLatch(1);

        var locker = executor.submit(() -> transactions.executeWithoutResult(status -> {
            assertThat(ledger.lockRun(run.runId())).isPresent();
            rowLocked.countDown();
            await(releaseRow);
        }));
        assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();

        var next = runState(LegacyImportRunStatus.STAGING, LegacyImportRunStage.SKELETONS_SUSPENDED);
        var contender = executor.submit(() -> inTransaction(() ->
                ledger.compareAndSetRun(run.runId(), LegacyImportRunState.initial(), next, null)));
        assertThatThrownBy(() -> contender.get(250, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        releaseRow.countDown();
        locker.get(5, TimeUnit.SECONDS);
        assertThat(contender.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void uniqueViolationAbortsTransactionAndWinnerIsRereadOnlyInNewLockedTransaction() {
        var winnerDefinition = definition("a", "a");
        var winner = inTransaction(() -> {
            ledger.acquireTransactionLock(winnerDefinition.runFingerprint());
            return ledger.createRun(winnerDefinition);
        });
        var contenderDefinition = definition("b", "b");

        assertThatThrownBy(() -> inTransaction(() -> {
                    ledger.acquireTransactionLock(contenderDefinition.runFingerprint());
                    return ledger.createRun(contenderDefinition);
                }))
                .isInstanceOf(LegacyImportUniqueConflictException.class)
                .hasMessage(LegacyImportUniqueConflictException.CODE);

        var rereadWinner = inTransaction(() -> {
            ledger.acquireTransactionLock(winnerDefinition.runFingerprint());
            return ledger.inspectOpenRun(winnerDefinition.importerKey()).orElseThrow();
        });
        assertThat(rereadWinner.runId()).isEqualTo(winner.runId());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM legacy_import_runs", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void cleansPlannedRunAndTenantPlanBeforeSkeletonCreation() {
        var definition = definition("a", "a");
        var tenantPlan = tenantPlan();

        var run = inTransaction(() -> {
            ledger.acquireTransactionLock(definition.runFingerprint());
            var created = ledger.createRun(definition);
            ledger.registerTenantPlan(created.runId(), definition.runFingerprint(), tenantPlan);
            var runCleanup = runState(
                    LegacyImportRunStatus.STAGING,
                    LegacyImportRunStage.CLEANUP_OBJECTS_PENDING);
            var tenantCleanup = tenantState(
                    LegacyImportTenantStatus.SKELETON,
                    LegacyImportRunStage.CLEANUP_OBJECTS_PENDING);
            assertThat(ledger.compareAndSetRun(
                            created.runId(), LegacyImportRunState.initial(), runCleanup, null))
                    .isTrue();
            assertThat(ledger.compareAndSetTenant(
                            created.runId(),
                            tenantPlan.tenantSlug(),
                            LegacyImportTenantState.initial(),
                            tenantCleanup))
                    .isTrue();
            assertThat(ledger.compareAndSetTenant(
                            created.runId(),
                            tenantPlan.tenantSlug(),
                            tenantCleanup,
                            tenantState(LegacyImportTenantStatus.CLEANED, LegacyImportRunStage.COMPLETED)))
                    .isTrue();
            assertThat(ledger.compareAndSetRun(
                            created.runId(),
                            runCleanup,
                            runState(LegacyImportRunStatus.CLEANED, LegacyImportRunStage.COMPLETED),
                            null))
                    .isTrue();
            return created;
        });

        assertThat(ledger.inspectTenants(run.runId())).singleElement().satisfies(provenance -> {
            assertThat(provenance.tenantIdSnapshot()).isNull();
            assertThat(provenance.liveTenantId()).isNull();
            assertThat(provenance.plan()).isEqualTo(tenantPlan);
            assertThat(provenance.state())
                    .isEqualTo(tenantState(LegacyImportTenantStatus.CLEANED, LegacyImportRunStage.COMPLETED));
        });
        assertThat(ledger.inspectRun(run.runId())).get().satisfies(completed -> {
            assertThat(completed.completedAt()).isNotNull();
            assertThat(completed.state())
                    .isEqualTo(runState(LegacyImportRunStatus.CLEANED, LegacyImportRunStage.COMPLETED));
        });
    }

    @Test
    void cleansSuspendedSkeletonBeforeAssetRegistrationAndRetainsTenantSnapshot() {
        var definition = definition("a", "a");
        var tenantPlan = tenantPlan();
        var tenantId = UUID.randomUUID();
        var skeletons = runState(
                LegacyImportRunStatus.STAGING,
                LegacyImportRunStage.SKELETONS_SUSPENDED);
        var tenantSkeleton = tenantState(
                LegacyImportTenantStatus.SKELETON,
                LegacyImportRunStage.SKELETONS_SUSPENDED);

        var run = inTransaction(() -> {
            ledger.acquireTransactionLock(definition.runFingerprint());
            var created = ledger.createRun(definition);
            ledger.registerTenantPlan(created.runId(), definition.runFingerprint(), tenantPlan);
            jdbcTemplate.update(
                    """
                    INSERT INTO tenants (id, slug, status, locale, currency, timezone)
                    VALUES (?, ?, 'suspended', 'ru-RU', 'RUB', 'Europe/Moscow')
                    """,
                    tenantId,
                    tenantPlan.tenantSlug());
            assertThat(ledger.attachTenantAndCompareAndSet(
                            created.runId(),
                            tenantPlan.tenantSlug(),
                            LegacyImportTenantState.initial(),
                            tenantSkeleton,
                            tenantId))
                    .isTrue();
            assertThat(ledger.compareAndSetRun(
                            created.runId(), LegacyImportRunState.initial(), skeletons, null))
                    .isTrue();
            return created;
        });

        inTransaction(() -> {
            ledger.acquireTransactionLock(definition.runFingerprint());
            var runCleanup = runState(
                    LegacyImportRunStatus.STAGING,
                    LegacyImportRunStage.CLEANUP_OBJECTS_PENDING);
            var tenantCleanup = tenantState(
                    LegacyImportTenantStatus.SKELETON,
                    LegacyImportRunStage.CLEANUP_OBJECTS_PENDING);
            assertThat(ledger.compareAndSetRun(run.runId(), skeletons, runCleanup, null)).isTrue();
            assertThat(ledger.compareAndSetTenant(
                            run.runId(), tenantPlan.tenantSlug(), tenantSkeleton, tenantCleanup))
                    .isTrue();
            jdbcTemplate.update("DELETE FROM tenants WHERE id = ?", tenantId);
            assertThat(ledger.compareAndSetTenant(
                            run.runId(),
                            tenantPlan.tenantSlug(),
                            tenantCleanup,
                            tenantState(LegacyImportTenantStatus.CLEANED, LegacyImportRunStage.COMPLETED)))
                    .isTrue();
            assertThat(ledger.compareAndSetRun(
                            run.runId(),
                            runCleanup,
                            runState(LegacyImportRunStatus.CLEANED, LegacyImportRunStage.COMPLETED),
                            null))
                    .isTrue();
            return null;
        });

        assertThat(ledger.inspectTenants(run.runId())).singleElement().satisfies(provenance -> {
            assertThat(provenance.tenantIdSnapshot()).isEqualTo(tenantId);
            assertThat(provenance.liveTenantId()).isNull();
            assertThat(provenance.plan().semanticFingerprint()).isEqualTo(tenantPlan.semanticFingerprint());
            assertThat(provenance.state().status()).isEqualTo(LegacyImportTenantStatus.CLEANED);
        });
    }

    @Test
    void includesPlannedOrphanAssetInCleanupAndRetainsHashesAndCanonicalKey() {
        var definition = definition("a", "a");
        var tenantPlan = tenantPlan();
        var tenantId = UUID.randomUUID();
        var assetPlan = new LegacyImportAssetPlan(
                tenantPlan.tenantSlug(),
                tenantId,
                "logo",
                hash("7"),
                hash("8"),
                128,
                "tenants/%s/logo/%s.png".formatted(tenantId, "8".repeat(64)));
        var runAssetsPending = runState(
                LegacyImportRunStatus.STAGING,
                LegacyImportRunStage.ASSETS_PENDING);
        var tenantAssetsPending = tenantState(
                LegacyImportTenantStatus.SKELETON,
                LegacyImportRunStage.ASSETS_PENDING);

        var run = inTransaction(() -> {
            ledger.acquireTransactionLock(definition.runFingerprint());
            var created = ledger.createRun(definition);
            ledger.registerTenantPlan(created.runId(), definition.runFingerprint(), tenantPlan);
            jdbcTemplate.update(
                    """
                    INSERT INTO tenants (id, slug, status, locale, currency, timezone)
                    VALUES (?, ?, 'suspended', 'ru-RU', 'RUB', 'Europe/Moscow')
                    """,
                    tenantId,
                    tenantPlan.tenantSlug());
            var tenantSkeleton = tenantState(
                    LegacyImportTenantStatus.SKELETON,
                    LegacyImportRunStage.SKELETONS_SUSPENDED);
            assertThat(ledger.attachTenantAndCompareAndSet(
                            created.runId(),
                            tenantPlan.tenantSlug(),
                            LegacyImportTenantState.initial(),
                            tenantSkeleton,
                            tenantId))
                    .isTrue();
            assertThat(ledger.compareAndSetTenant(
                            created.runId(), tenantPlan.tenantSlug(), tenantSkeleton, tenantAssetsPending))
                    .isTrue();
            ledger.registerAssetPlan(created.runId(), definition.runFingerprint(), assetPlan);
            var runSkeletons = runState(
                    LegacyImportRunStatus.STAGING,
                    LegacyImportRunStage.SKELETONS_SUSPENDED);
            assertThat(ledger.compareAndSetRun(
                            created.runId(), LegacyImportRunState.initial(), runSkeletons, null))
                    .isTrue();
            assertThat(ledger.compareAndSetRun(
                            created.runId(), runSkeletons, runAssetsPending, null))
                    .isTrue();
            return created;
        });

        inTransaction(() -> {
            ledger.acquireTransactionLock(definition.runFingerprint());
            var runCleanup = runState(
                    LegacyImportRunStatus.STAGING,
                    LegacyImportRunStage.CLEANUP_OBJECTS_PENDING);
            var tenantCleanup = tenantState(
                    LegacyImportTenantStatus.SKELETON,
                    LegacyImportRunStage.CLEANUP_OBJECTS_PENDING);
            var assetCleanup = assetState(
                    LegacyImportAssetStatus.PLANNED,
                    LegacyImportRunStage.CLEANUP_OBJECTS_PENDING);
            assertThat(ledger.compareAndSetRun(run.runId(), runAssetsPending, runCleanup, null)).isTrue();
            assertThat(ledger.compareAndSetAsset(
                            run.runId(),
                            tenantPlan.tenantSlug(),
                            assetPlan.assetKind(),
                            assetPlan.contentSha256(),
                            LegacyImportAssetState.initial(),
                            assetCleanup))
                    .isTrue();
            assertThat(ledger.compareAndSetTenant(
                            run.runId(), tenantPlan.tenantSlug(), tenantAssetsPending, tenantCleanup))
                    .isTrue();
            jdbcTemplate.update("DELETE FROM tenants WHERE id = ?", tenantId);

            assertThat(ledger.lockAssetCleanupCandidates(run.runId())).singleElement().satisfies(candidate -> {
                assertThat(candidate.tenantIdSnapshot()).isEqualTo(tenantId);
                assertThat(candidate.assetIdSnapshot()).isNull();
                assertThat(candidate.contentSha256()).isEqualTo(assetPlan.contentSha256());
                assertThat(candidate.canonicalObjectKey()).isEqualTo(assetPlan.canonicalObjectKey());
            });
            assertThat(ledger.compareAndSetAsset(
                            run.runId(),
                            tenantPlan.tenantSlug(),
                            assetPlan.assetKind(),
                            assetPlan.contentSha256(),
                            assetCleanup,
                            assetState(LegacyImportAssetStatus.CLEANED, LegacyImportRunStage.COMPLETED)))
                    .isTrue();
            assertThat(ledger.compareAndSetTenant(
                            run.runId(),
                            tenantPlan.tenantSlug(),
                            tenantCleanup,
                            tenantState(LegacyImportTenantStatus.CLEANED, LegacyImportRunStage.COMPLETED)))
                    .isTrue();
            assertThat(ledger.compareAndSetRun(
                            run.runId(),
                            runCleanup,
                            runState(LegacyImportRunStatus.CLEANED, LegacyImportRunStage.COMPLETED),
                            null))
                    .isTrue();
            return null;
        });

        assertThat(ledger.inspectAssets(run.runId())).singleElement().satisfies(provenance -> {
            assertThat(provenance.assetIdSnapshot()).isNull();
            assertThat(provenance.liveAssetId()).isNull();
            assertThat(provenance.liveTenantId()).isNull();
            assertThat(provenance.sourceSha256()).isEqualTo(assetPlan.sourceSha256());
            assertThat(provenance.contentSha256()).isEqualTo(assetPlan.contentSha256());
            assertThat(provenance.state().status()).isEqualTo(LegacyImportAssetStatus.CLEANED);
        });
    }

    @Test
    void preservesHistoricalProvenanceAcrossDependencyOrderedDatabaseCleanup() {
        var definition = definition("a", "a");
        var tenantId = UUID.randomUUID();
        var assetId = UUID.randomUUID();
        var tenantPlan = tenantPlan();
        var assetPlan = new LegacyImportAssetPlan(
                tenantPlan.tenantSlug(),
                tenantId,
                "logo",
                hash("7"),
                hash("8"),
                128,
                "tenants/%s/logo/%s.png".formatted(tenantId, "8".repeat(64)));

        var run = inTransaction(() -> {
            ledger.acquireTransactionLock(definition.runFingerprint());
            var created = ledger.createRun(definition);
            ledger.registerTenantPlan(created.runId(), definition.runFingerprint(), tenantPlan);
            jdbcTemplate.update(
                    """
                    INSERT INTO tenants (id, slug, status, locale, currency, timezone)
                    VALUES (?, ?, 'suspended', 'ru-RU', 'RUB', 'Europe/Moscow')
                    """,
                    tenantId,
                    tenantPlan.tenantSlug());
            var attached = tenantState(LegacyImportTenantStatus.SKELETON, LegacyImportRunStage.SKELETONS_SUSPENDED);
            assertThat(ledger.attachTenantAndCompareAndSet(
                            created.runId(), tenantPlan.tenantSlug(), LegacyImportTenantState.initial(), attached, tenantId))
                    .isTrue();
            assertThat(ledger.compareAndSetTenant(
                            created.runId(),
                            tenantPlan.tenantSlug(),
                            attached,
                            tenantState(LegacyImportTenantStatus.SKELETON, LegacyImportRunStage.ASSETS_PENDING)))
                    .isTrue();
            ledger.registerAssetPlan(created.runId(), definition.runFingerprint(), assetPlan);
            jdbcTemplate.update(
                    """
                    INSERT INTO tenant_assets (
                        id, tenant_id, object_key, asset_type, content_type,
                        size_bytes, sha256, status
                    ) VALUES (?, ?, ?, 'logo', 'image/png', 128, ?, 'active')
                    """,
                    assetId,
                    tenantId,
                    assetPlan.canonicalObjectKey(),
                    assetPlan.contentSha256().value());
            assertThat(ledger.attachAssetAndCompareAndSet(
                            created.runId(),
                            tenantPlan.tenantSlug(),
                            assetPlan.assetKind(),
                            assetPlan.contentSha256(),
                            LegacyImportAssetState.initial(),
                            assetState(LegacyImportAssetStatus.CREATED, LegacyImportRunStage.ASSETS_STAGED),
                            assetId))
                    .isTrue();
            assertThat(ledger.compareAndSetTenant(
                            created.runId(),
                            tenantPlan.tenantSlug(),
                            tenantState(LegacyImportTenantStatus.SKELETON, LegacyImportRunStage.ASSETS_PENDING),
                            tenantState(LegacyImportTenantStatus.STAGED, LegacyImportRunStage.ASSETS_STAGED)))
                    .isTrue();
            assertThat(ledger.compareAndSetRun(
                            created.runId(),
                            LegacyImportRunState.initial(),
                            runState(LegacyImportRunStatus.STAGING, LegacyImportRunStage.SKELETONS_SUSPENDED),
                            null))
                    .isTrue();
            assertThat(ledger.compareAndSetRun(
                            created.runId(),
                            runState(LegacyImportRunStatus.STAGING, LegacyImportRunStage.SKELETONS_SUSPENDED),
                            runState(LegacyImportRunStatus.STAGING, LegacyImportRunStage.ASSETS_PENDING),
                            null))
                    .isTrue();
            assertThat(ledger.compareAndSetRun(
                            created.runId(),
                            runState(LegacyImportRunStatus.STAGING, LegacyImportRunStage.ASSETS_PENDING),
                            runState(LegacyImportRunStatus.STAGING, LegacyImportRunStage.ASSETS_STAGED),
                            null))
                    .isTrue();
            return created;
        });

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                        jdbcTemplate.update("DELETE FROM tenant_assets WHERE id = ?", assetId)))
                .rootCause()
                .isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getSQLState())
                .isEqualTo("23514");

        inTransaction(() -> {
            ledger.acquireTransactionLock(definition.runFingerprint());
            assertThat(ledger.compareAndSetRun(
                            run.runId(),
                            runState(LegacyImportRunStatus.STAGING, LegacyImportRunStage.ASSETS_STAGED),
                            runState(LegacyImportRunStatus.STAGING, LegacyImportRunStage.CLEANUP_OBJECTS_PENDING),
                            null))
                    .isTrue();
            assertThat(ledger.compareAndSetAsset(
                            run.runId(),
                            tenantPlan.tenantSlug(),
                            assetPlan.assetKind(),
                            assetPlan.contentSha256(),
                            assetState(LegacyImportAssetStatus.CREATED, LegacyImportRunStage.ASSETS_STAGED),
                            assetState(LegacyImportAssetStatus.CREATED, LegacyImportRunStage.CLEANUP_OBJECTS_PENDING)))
                    .isTrue();
            assertThat(ledger.compareAndSetTenant(
                            run.runId(),
                            tenantPlan.tenantSlug(),
                            tenantState(LegacyImportTenantStatus.STAGED, LegacyImportRunStage.ASSETS_STAGED),
                            tenantState(LegacyImportTenantStatus.STAGED, LegacyImportRunStage.CLEANUP_OBJECTS_PENDING)))
                    .isTrue();

            jdbcTemplate.update("DELETE FROM tenant_assets WHERE id = ?", assetId);
            jdbcTemplate.update("DELETE FROM tenants WHERE id = ?", tenantId);

            var candidates = ledger.lockAssetCleanupCandidates(run.runId());
            assertThat(candidates).singleElement().satisfies(candidate -> {
                assertThat(candidate.tenantIdSnapshot()).isEqualTo(tenantId);
                assertThat(candidate.assetIdSnapshot()).isEqualTo(assetId);
                assertThat(candidate.canonicalObjectKey()).isEqualTo(assetPlan.canonicalObjectKey());
                assertThat(candidate.toString()).doesNotContain(assetPlan.canonicalObjectKey());
            });
            assertThat(ledger.compareAndSetAsset(
                            run.runId(),
                            tenantPlan.tenantSlug(),
                            assetPlan.assetKind(),
                            assetPlan.contentSha256(),
                            assetState(LegacyImportAssetStatus.CREATED, LegacyImportRunStage.CLEANUP_OBJECTS_PENDING),
                            assetState(LegacyImportAssetStatus.CLEANED, LegacyImportRunStage.COMPLETED)))
                    .isTrue();
            assertThat(ledger.compareAndSetTenant(
                            run.runId(),
                            tenantPlan.tenantSlug(),
                            tenantState(LegacyImportTenantStatus.STAGED, LegacyImportRunStage.CLEANUP_OBJECTS_PENDING),
                            tenantState(LegacyImportTenantStatus.CLEANED, LegacyImportRunStage.COMPLETED)))
                    .isTrue();
            assertThat(ledger.compareAndSetRun(
                            run.runId(),
                            runState(LegacyImportRunStatus.STAGING, LegacyImportRunStage.CLEANUP_OBJECTS_PENDING),
                            runState(LegacyImportRunStatus.CLEANED, LegacyImportRunStage.COMPLETED),
                            null))
                    .isTrue();
            return null;
        });

        assertThat(ledger.inspectTenants(run.runId())).singleElement().satisfies(provenance -> {
            assertThat(provenance.tenantIdSnapshot()).isEqualTo(tenantId);
            assertThat(provenance.liveTenantId()).isNull();
            assertThat(provenance.state().status()).isEqualTo(LegacyImportTenantStatus.CLEANED);
        });
        assertThat(ledger.inspectAssets(run.runId())).singleElement().satisfies(provenance -> {
            assertThat(provenance.tenantIdSnapshot()).isEqualTo(tenantId);
            assertThat(provenance.assetIdSnapshot()).isEqualTo(assetId);
            assertThat(provenance.liveTenantId()).isNull();
            assertThat(provenance.liveAssetId()).isNull();
            assertThat(provenance.state().status()).isEqualTo(LegacyImportAssetStatus.CLEANED);
        });
        assertThat(ledger.inspectRun(run.runId())).get().satisfies(completed -> {
            assertThat(completed.completedAt()).isNotNull();
            assertThat(completed.state())
                    .isEqualTo(runState(LegacyImportRunStatus.CLEANED, LegacyImportRunStage.COMPLETED));
        });
    }

    private static LegacyImportRunDefinition definition(String fingerprintDigit, String contentDigit) {
        return new LegacyImportRunDefinition(
                "legacy-menu",
                "legacy-menu-v1",
                1,
                "test",
                fingerprint(fingerprintDigit),
                hash(contentDigit),
                hash(nextDigit(contentDigit, 1)),
                hash(nextDigit(contentDigit, 2)),
                hash(nextDigit(contentDigit, 3)));
    }

    private static LegacyImportTenantPlan tenantPlan() {
        return new LegacyImportTenantPlan(
                "tenant-a",
                1,
                hash("1"),
                hash("2"),
                hash("3"),
                hash("4"),
                hash("5"),
                hash("6"));
    }

    private static LegacyImportRunFingerprint fingerprint(String digit) {
        return new LegacyImportRunFingerprint(digit.repeat(64));
    }

    private static LegacyImportSha256 hash(String digit) {
        return new LegacyImportSha256(digit.repeat(64));
    }

    private static String nextDigit(String digit, int increment) {
        return Integer.toHexString((Integer.parseInt(digit, 16) + increment) & 0xf);
    }

    private static LegacyImportRunState runState(
            LegacyImportRunStatus status,
            LegacyImportRunStage stage) {
        return new LegacyImportRunState(status, stage);
    }

    private static LegacyImportTenantState tenantState(
            LegacyImportTenantStatus status,
            LegacyImportRunStage stage) {
        return new LegacyImportTenantState(status, stage);
    }

    private static LegacyImportAssetState assetState(
            LegacyImportAssetStatus status,
            LegacyImportRunStage stage) {
        return new LegacyImportAssetState(status, stage);
    }

    private static <T> T inTransaction(java.util.concurrent.Callable<T> callback) {
        return transactions.execute(status -> {
            try {
                return callback.call();
            } catch (RuntimeException runtimeException) {
                throw runtimeException;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test synchronization");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test synchronization", exception);
        }
    }

    private static Flyway flyway(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static Connection connection(String schema) throws SQLException {
        var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setSchema(schema);
        return connection;
    }

    private static String currentVersion(String schema) throws SQLException {
        try (var connection = connection(schema);
                var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static java.util.Set<String> tables(String schema) throws SQLException {
        try (var connection = connection(schema);
                var statement = connection.prepareStatement(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = ?")) {
            statement.setString(1, schema);
            try (var result = statement.executeQuery()) {
                var tables = new java.util.HashSet<String>();
                while (result.next()) {
                    tables.add(result.getString(1));
                }
                return tables;
            }
        }
    }

    private static Map<String, Integer> migrationChecksums(String schema) throws SQLException {
        try (var connection = connection(schema);
                var statement = connection.prepareStatement(
                        "SELECT version, checksum FROM flyway_schema_history WHERE success AND version IS NOT NULL")) {
            try (var result = statement.executeQuery()) {
                var checksums = new java.util.HashMap<String, Integer>();
                while (result.next()) {
                    checksums.put(result.getString(1), result.getInt(2));
                }
                return checksums;
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {}
}
