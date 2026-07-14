package com.salamu.menu.menu.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.salamu.menu.menu.api.MenuAssetReference;
import com.salamu.menu.menu.api.MenuCleanupCandidate;
import com.salamu.menu.menu.api.MenuProvisioningConflictException;
import com.salamu.menu.menu.api.MenuProvisioningDisposition;
import com.salamu.menu.menu.api.MenuProvisioningService;
import com.salamu.menu.menu.api.MenuSemanticHash;
import com.salamu.menu.menu.api.PublishedMenuSnapshot;
import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantId;

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
class MenuProvisioningPostgreSqlIntegrationTests {

    private static final ProvisioningRunFingerprint RUN = new ProvisioningRunFingerprint("a".repeat(64));
    private static final ProvisioningRunFingerprint OTHER_RUN = new ProvisioningRunFingerprint("b".repeat(64));
    private static final TenantId TENANT_A = new TenantId(
            UUID.fromString("10000000-0000-4000-8000-000000000001"));
    private static final TenantId TENANT_B = new TenantId(
            UUID.fromString("20000000-0000-4000-8000-000000000002"));
    private static final UUID RESTAURANT_A = UUID.fromString("11000000-0000-4000-8000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("22000000-0000-4000-8000-000000000002");
    private static final TenantAssetId CATEGORY_ASSET_A = new TenantAssetId(
            UUID.fromString("fa000000-0000-4000-8000-000000000001"));
    private static final TenantAssetId ITEM_ASSET_A = new TenantAssetId(
            UUID.fromString("fa000000-0000-4000-8000-000000000002"));

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine3.21")
            .withDatabaseName("menu_provisioning")
            .withUsername("menu_provisioning")
            .withPassword("menu_provisioning");

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
    private MenuProvisioningService provisioningService;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE tenants CASCADE");
        insertTenant(TENANT_A, "tenant-a", "suspended", RESTAURANT_A);
        insertTenant(TENANT_B, "tenant-b", "suspended", RESTAURANT_B);
        insertAsset(TENANT_A, CATEGORY_ASSET_A, "menu_category_image", "c".repeat(64));
        insertAsset(TENANT_A, ITEM_ASSET_A, "menu_item_image", "d".repeat(64));
    }

    @Test
    void writerIsMandatoryBulkCreatesFortyTwoItemsAndEqualRerunIsNoOp() {
        var snapshot = snapshot(TENANT_A, RESTAURANT_A, "tenant-a", 6, 7, CATEGORY_ASSET_A, ITEM_ASSET_A);

        assertThatThrownBy(() -> provisioningService.writeMenuSnapshot(snapshot, RUN))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> provisioningService.verifyPreparedMenu(snapshot, RUN))
                .isInstanceOf(IllegalTransactionStateException.class);

        var created = transactions.execute(status -> provisioningService.writeMenuSnapshot(snapshot, RUN));
        var unchanged = transactions.execute(status -> provisioningService.writeMenuSnapshot(snapshot, RUN));
        var inspected = provisioningService.inspectMenuSnapshot(TENANT_A, RESTAURANT_A, 1).orElseThrow();

        assertThat(created.disposition()).isEqualTo(MenuProvisioningDisposition.CREATED);
        assertThat(unchanged.disposition()).isEqualTo(MenuProvisioningDisposition.UNCHANGED);
        assertThat(unchanged.menuVersionId()).isEqualTo(created.menuVersionId());
        assertThat(unchanged.semanticHash()).isEqualTo(created.semanticHash());
        assertThat(inspected.snapshot()).isEqualTo(snapshot);
        assertThat(inspected.categoryCount()).isEqualTo(6);
        assertThat(inspected.itemCount()).isEqualTo(42);
        assertThat(inspected.snapshot().categories().getFirst().imageAsset())
                .isEqualTo(new MenuAssetReference(CATEGORY_ASSET_A, "c".repeat(64)));
        assertThat(inspected.snapshot().items().getFirst().imageAsset())
                .isEqualTo(new MenuAssetReference(ITEM_ASSET_A, "d".repeat(64)));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM menu_items WHERE tenant_id = ?", Long.class, TENANT_A.value()))
                .isEqualTo(42L);
    }

    @Test
    void sameVersionMismatchAndForeignRunAreTypedConflictsWithoutMutation() {
        var snapshot = snapshot(TENANT_A, RESTAURANT_A, "tenant-a", 2, 2, null, null);
        transactions.executeWithoutResult(status -> provisioningService.writeMenuSnapshot(snapshot, RUN));

        var changedItems = new ArrayList<>(snapshot.items());
        var first = changedItems.getFirst();
        changedItems.set(0, new PublishedMenuSnapshot.Item(
                first.menuItemId(), first.categoryId(), "Другое имя", first.description(), first.priceMinor(),
                first.currency(), first.imageAsset(), first.available(), first.sortOrder()));
        var changed = new PublishedMenuSnapshot(
                snapshot.tenantId(), snapshot.restaurantId(), snapshot.version(), snapshot.publishedAt(),
                snapshot.currency(), snapshot.categories(), changedItems);

        assertThatThrownBy(() -> transactions.executeWithoutResult(
                        status -> provisioningService.writeMenuSnapshot(changed, RUN)))
                .isInstanceOfSatisfying(MenuProvisioningConflictException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(MenuProvisioningConflictException.Code.SNAPSHOT_CONFLICT));
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                        status -> provisioningService.writeMenuSnapshot(snapshot, OTHER_RUN)))
                .isInstanceOfSatisfying(MenuProvisioningConflictException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(MenuProvisioningConflictException.Code.FOREIGN_PROVENANCE));

        assertThat(provisioningService.inspectMenuSnapshot(TENANT_A, RESTAURANT_A, 1).orElseThrow().snapshot())
                .isEqualTo(snapshot);
    }

    @Test
    void preparedVerificationSeesUncommittedBulkWritesAndOuterRollbackRemovesThem() {
        var snapshot = snapshot(TENANT_A, RESTAURANT_A, "tenant-a", 3, 3, null, null);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    var result = provisioningService.writeMenuSnapshot(snapshot, RUN);
                    var verified = provisioningService.verifyPreparedMenu(snapshot, RUN);
                    assertThat(verified.semanticHash()).isEqualTo(result.semanticHash());
                    assertThat(jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM menu_items WHERE tenant_id = ?", Long.class, TENANT_A.value()))
                            .isEqualTo(9L);
                    throw new RollbackMarker();
                }))
                .isInstanceOf(RollbackMarker.class);

        assertThat(provisioningService.inspectMenuSnapshot(TENANT_A, RESTAURANT_A, 1)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM menu_versions WHERE tenant_id = ?", Long.class, TENANT_A.value()))
                .isZero();
    }

    @Test
    void identicalPublicIdsAndVersionsRemainStrictlyTenantScoped() {
        var snapshotA = snapshot(TENANT_A, RESTAURANT_A, "tenant-a", 2, 2, null, null);
        var snapshotB = snapshot(TENANT_B, RESTAURANT_B, "tenant-b", 2, 2, null, null);
        transactions.executeWithoutResult(status -> {
            provisioningService.writeMenuSnapshot(snapshotA, RUN);
            provisioningService.writeMenuSnapshot(snapshotB, RUN);
        });

        assertThat(provisioningService.inspectMenuSnapshot(TENANT_A, RESTAURANT_A, 1).orElseThrow().snapshot())
                .isEqualTo(snapshotA);
        assertThat(provisioningService.inspectMenuSnapshot(TENANT_B, RESTAURANT_B, 1).orElseThrow().snapshot())
                .isEqualTo(snapshotB);
        assertThat(provisioningService.inspectMenuSnapshot(TENANT_A, RESTAURANT_B, 1)).isEmpty();
        assertThat(provisioningService.inspectMenuSnapshot(TENANT_B, RESTAURANT_A, 1)).isEmpty();
    }

    @Test
    void finalWriterFailsClosedForPendingDeletedWrongKindOrCrossTenantAssetIds() {
        var snapshot = snapshot(TENANT_A, RESTAURANT_A, "tenant-a", 2, 2, CATEGORY_ASSET_A, ITEM_ASSET_A);

        var categoriesWithWrongSha = new ArrayList<>(snapshot.categories());
        var firstCategory = categoriesWithWrongSha.getFirst();
        categoriesWithWrongSha.set(0, new PublishedMenuSnapshot.Category(
                firstCategory.categoryId(), firstCategory.name(), firstCategory.description(),
                new MenuAssetReference(CATEGORY_ASSET_A, "e".repeat(64)),
                firstCategory.sortOrder(), firstCategory.available()));
        var wrongSha = new PublishedMenuSnapshot(
                snapshot.tenantId(), snapshot.restaurantId(), snapshot.version(), snapshot.publishedAt(),
                snapshot.currency(), categoriesWithWrongSha, snapshot.items());
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                        status -> provisioningService.writeMenuSnapshot(wrongSha, RUN)))
                .isInstanceOfSatisfying(MenuProvisioningConflictException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(MenuProvisioningConflictException.Code.SNAPSHOT_CONFLICT));

        jdbcTemplate.update(
                "UPDATE tenant_assets SET status = 'pending' WHERE tenant_id = ? AND id = ?",
                TENANT_A.value(),
                CATEGORY_ASSET_A.value());
        assertAssetConflict(snapshot);

        jdbcTemplate.update(
                "UPDATE tenant_assets SET status = 'deleted' WHERE tenant_id = ? AND id = ?",
                TENANT_A.value(),
                CATEGORY_ASSET_A.value());
        assertAssetConflict(snapshot);

        jdbcTemplate.update(
                "UPDATE tenant_assets SET status = 'active', asset_type = 'menu_item_image' WHERE tenant_id = ? AND id = ?",
                TENANT_A.value(),
                CATEGORY_ASSET_A.value());
        assertAssetConflict(snapshot);

        jdbcTemplate.update("DELETE FROM tenant_assets WHERE tenant_id = ?", TENANT_A.value());
        insertAsset(TENANT_B, CATEGORY_ASSET_A, "menu_category_image", "c".repeat(64));
        insertAsset(TENANT_B, ITEM_ASSET_A, "menu_item_image", "d".repeat(64));
        assertAssetConflict(snapshot);
    }

    @Test
    void zeroWriteInspectionUsesActualAssetShaAndChangesSemanticHashForStaleContentReference() {
        var snapshot = snapshot(TENANT_A, RESTAURANT_A, "tenant-a", 2, 2, CATEGORY_ASSET_A, ITEM_ASSET_A);
        transactions.executeWithoutResult(status -> provisioningService.writeMenuSnapshot(snapshot, RUN));
        var baseline = provisioningService.inspectMenuSnapshot(TENANT_A, RESTAURANT_A, 1).orElseThrow();

        var replacementSha = "e".repeat(64);
        jdbcTemplate.update("""
                UPDATE tenant_assets
                   SET sha256 = ?, object_key = ?
                 WHERE tenant_id = ? AND id = ?
                """, replacementSha,
                "tenants/%s/menu_category_image/%s.png".formatted(TENANT_A.value(), replacementSha),
                TENANT_A.value(), CATEGORY_ASSET_A.value());

        var changed = provisioningService.inspectMenuSnapshot(TENANT_A, RESTAURANT_A, 1).orElseThrow();
        assertThat(changed.semanticHash()).isNotEqualTo(baseline.semanticHash());
        assertThat(changed.snapshot().categories().getFirst().imageAsset())
                .isEqualTo(new MenuAssetReference(CATEGORY_ASSET_A, replacementSha));
        assertThat(changed.snapshot().items().getFirst().imageAsset())
                .isEqualTo(new MenuAssetReference(ITEM_ASSET_A, "d".repeat(64)));

        jdbcTemplate.update(
                "UPDATE tenant_assets SET status = 'deleted' WHERE tenant_id = ? AND id = ?",
                TENANT_A.value(), CATEGORY_ASSET_A.value());
        assertThatThrownBy(() -> provisioningService.inspectMenuSnapshot(TENANT_A, RESTAURANT_A, 1))
                .isInstanceOfSatisfying(MenuProvisioningConflictException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(MenuProvisioningConflictException.Code.STORAGE_CONFLICT));
    }

    @Test
    void cleanupRequiresExactHashRunSuspendedLifecycleAndNoOrderReference() {
        var snapshot = snapshot(
                TENANT_A, RESTAURANT_A, "tenant-a", 2, 2, CATEGORY_ASSET_A, ITEM_ASSET_A);
        var result = transactions.execute(status -> provisioningService.writeMenuSnapshot(snapshot, RUN));
        var candidate = new MenuCleanupCandidate(TENANT_A, RESTAURANT_A, 1, result.semanticHash());

        assertThatThrownBy(() -> provisioningService.cleanupMenuSnapshot(candidate, RUN))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                        status -> provisioningService.cleanupMenuSnapshot(candidate, OTHER_RUN)))
                .isInstanceOfSatisfying(MenuProvisioningConflictException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(MenuProvisioningConflictException.Code.FOREIGN_PROVENANCE));
        var wrongHash = new MenuCleanupCandidate(
                TENANT_A, RESTAURANT_A, 1, new MenuSemanticHash(MenuSemanticHash.CURRENT_SCHEMA_VERSION, "f".repeat(64)));
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                        status -> provisioningService.cleanupMenuSnapshot(wrongHash, RUN)))
                .isInstanceOfSatisfying(MenuProvisioningConflictException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(MenuProvisioningConflictException.Code.SNAPSHOT_CONFLICT));

        jdbcTemplate.update(
                "UPDATE tenant_assets SET status = 'pending' WHERE tenant_id = ? AND id = ?",
                TENANT_A.value(),
                CATEGORY_ASSET_A.value());
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                        status -> provisioningService.cleanupMenuSnapshot(candidate, RUN)))
                .isInstanceOfSatisfying(MenuProvisioningConflictException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(MenuProvisioningConflictException.Code.STORAGE_CONFLICT));
        jdbcTemplate.update(
                "UPDATE tenant_assets SET status = 'active' WHERE tenant_id = ? AND id = ?",
                TENANT_A.value(),
                CATEGORY_ASSET_A.value());

        insertOrder(TENANT_A, RESTAURANT_A, result.menuVersionId());
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                        status -> provisioningService.cleanupMenuSnapshot(candidate, RUN)))
                .isInstanceOfSatisfying(MenuProvisioningConflictException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(MenuProvisioningConflictException.Code.CLEANUP_FORBIDDEN));
        jdbcTemplate.update("DELETE FROM orders WHERE tenant_id = ?", TENANT_A.value());

        transactions.executeWithoutResult(status -> provisioningService.cleanupMenuSnapshot(candidate, RUN));
        transactions.executeWithoutResult(status -> provisioningService.cleanupMenuSnapshot(candidate, RUN));
        assertThat(provisioningService.inspectMenuSnapshot(TENANT_A, RESTAURANT_A, 1)).isEmpty();
    }

    @Test
    void activeTenantAndPartialPersistedSnapshotFailClosed() {
        var snapshot = snapshot(TENANT_A, RESTAURANT_A, "tenant-a", 2, 2, null, null);
        transactions.executeWithoutResult(status -> provisioningService.writeMenuSnapshot(snapshot, RUN));
        jdbcTemplate.update("UPDATE tenants SET status = 'active' WHERE id = ?", TENANT_A.value());
        var inspection = provisioningService.inspectMenuSnapshot(TENANT_A, RESTAURANT_A, 1);
        assertThat(inspection).isPresent();
        var candidate = new MenuCleanupCandidate(
                TENANT_A, RESTAURANT_A, 1, inspection.orElseThrow().semanticHash());
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                        status -> provisioningService.writeMenuSnapshot(snapshot, RUN)))
                .isInstanceOfSatisfying(MenuProvisioningConflictException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(MenuProvisioningConflictException.Code.TENANT_LIFECYCLE_CONFLICT));
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                        status -> provisioningService.cleanupMenuSnapshot(candidate, RUN)))
                .isInstanceOfSatisfying(MenuProvisioningConflictException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(MenuProvisioningConflictException.Code.TENANT_LIFECYCLE_CONFLICT));

        jdbcTemplate.update("DELETE FROM menu_versions WHERE tenant_id = ?", TENANT_A.value());
        jdbcTemplate.update("UPDATE tenants SET status = 'suspended' WHERE id = ?", TENANT_A.value());
        jdbcTemplate.update("""
                INSERT INTO menu_versions (id, tenant_id, restaurant_id, version, status, published_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, 1, 'published', '2026-07-01T00:00:00Z')
                """, UUID.randomUUID(), TENANT_A.value(), RESTAURANT_A);
        assertThatThrownBy(() -> provisioningService.inspectMenuSnapshot(TENANT_A, RESTAURANT_A, 1))
                .isInstanceOfSatisfying(MenuProvisioningConflictException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(MenuProvisioningConflictException.Code.PERSISTED_STATE_INVALID));
    }

    private void insertTenant(TenantId tenantId, String slug, String status, UUID restaurantId) {
        jdbcTemplate.update("""
                INSERT INTO tenants (id, slug, status, locale, currency, timezone)
                VALUES (?, ?, ?, 'ru-RU', 'RUB', 'Europe/Moscow')
                """, tenantId.value(), slug, status);
        jdbcTemplate.update("""
                INSERT INTO restaurants (id, tenant_id, slug, display_name, status, is_default)
                VALUES (?, ?, 'main', ?, 'active', true)
                """, restaurantId, tenantId.value(), slug);
    }

    private void assertAssetConflict(PublishedMenuSnapshot snapshot) {
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                        status -> provisioningService.writeMenuSnapshot(snapshot, RUN)))
                .isInstanceOfSatisfying(MenuProvisioningConflictException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(MenuProvisioningConflictException.Code.STORAGE_CONFLICT));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM menu_versions WHERE tenant_id = ?", Long.class, snapshot.tenantId().value()))
                .isZero();
    }

    private void insertAsset(TenantId tenantId, TenantAssetId assetId, String kind, String sha256) {
        jdbcTemplate.update("""
                INSERT INTO tenant_assets (
                    id, tenant_id, object_key, asset_type, content_type, size_bytes, sha256, status
                ) VALUES (?, ?, ?, ?, 'image/png', 100, ?, 'active')
                """, assetId.value(), tenantId.value(),
                "tenants/%s/%s/%s.png".formatted(tenantId.value(), kind, sha256), kind, sha256);
    }

    private void insertOrder(TenantId tenantId, UUID restaurantId, UUID menuVersionId) {
        jdbcTemplate.update("""
                INSERT INTO orders (
                    id, tenant_id, restaurant_id, menu_version_id, order_number,
                    idempotency_key, idempotency_payload_hash, status, order_type, channel,
                    customer_name, customer_phone, client_total_minor, server_total_minor,
                    currency, client_created_at, client_menu_version
                ) VALUES (?, ?, ?, ?, 1, 'cleanup-reference', ?, 'new', 'pickup', 'import',
                          'Test', '+70000000000', 0, 0, 'RUB', '2026-07-01T00:00:00Z', 1)
                """, UUID.randomUUID(), tenantId.value(), restaurantId, menuVersionId, "e".repeat(64));
    }

    private static PublishedMenuSnapshot snapshot(
            TenantId tenantId,
            UUID restaurantId,
            String namePrefix,
            int categoryCount,
            int itemsPerCategory,
            TenantAssetId categoryAsset,
            TenantAssetId itemAsset) {
        List<PublishedMenuSnapshot.Category> categories = new ArrayList<>();
        List<PublishedMenuSnapshot.Item> items = new ArrayList<>();
        for (int categoryIndex = 0; categoryIndex < categoryCount; categoryIndex++) {
            var categoryId = "category-%02d".formatted(categoryIndex);
            categories.add(new PublishedMenuSnapshot.Category(
                    categoryId,
                    namePrefix + " category " + categoryIndex,
                    categoryIndex == 0 ? "Описание" : null,
                    categoryIndex == 0 ? reference(categoryAsset, "c".repeat(64)) : null,
                    categoryIndex,
                    true));
            for (int itemIndex = 0; itemIndex < itemsPerCategory; itemIndex++) {
                items.add(new PublishedMenuSnapshot.Item(
                        "item-%02d-%02d".formatted(categoryIndex, itemIndex),
                        categoryId,
                        namePrefix + " item " + categoryIndex + '-' + itemIndex,
                        itemIndex == 0 ? "Описание" : null,
                        categoryIndex * 10_000L + itemIndex * 100L,
                        "RUB",
                        categoryIndex == 0 && itemIndex == 0 ? reference(itemAsset, "d".repeat(64)) : null,
                        itemIndex % 2 == 0,
                        itemIndex));
            }
        }
        return new PublishedMenuSnapshot(
                tenantId,
                restaurantId,
                1,
                Instant.parse("2026-07-01T00:00:00Z"),
                "RUB",
                categories,
                items);
    }

    private static MenuAssetReference reference(TenantAssetId assetId, String sha256) {
        return assetId == null ? null : new MenuAssetReference(assetId, sha256);
    }

    private static final class RollbackMarker extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
