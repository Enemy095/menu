package com.salamu.menu.tenancy.internal.infrastructure.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;
import com.salamu.menu.tenancy.api.TenantAsset;
import com.salamu.menu.tenancy.api.TenantAssetCleanupCandidate;
import com.salamu.menu.tenancy.api.TenantAssetCleanupProof;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantAssetImportRequest;
import com.salamu.menu.tenancy.api.TenantAssetNotFoundException;
import com.salamu.menu.tenancy.api.TenantAssetObjectCleanupDisposition;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningConflictException;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningDisposition;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningProvenance;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningService;
import com.salamu.menu.tenancy.api.TenantAssetService;
import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.api.TenantLifecycle;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@ActiveProfiles({"test", "legacy-import"})
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantAssetMinioIntegrationTests {

    private static final String BUCKET = "tenant-assets-integration";
    private static final String ACCESS_KEY = "integration-access";
    private static final String SECRET_KEY = "integration-secret";
    private static final TenantId TENANT_A =
            new TenantId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    private static final TenantId TENANT_B =
            new TenantId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
    private static final ProvisioningRunFingerprint RUN_A =
            new ProvisioningRunFingerprint("1".repeat(64));
    private static final ProvisioningRunFingerprint RUN_B =
            new ProvisioningRunFingerprint("2".repeat(64));
    private static final String PNG_SHA256 =
            "275f1bcbbb585c71e3b2184304eccfa0e37de92022ca3b6f4e9c10df32318d85";

    static final PostgreSQLContainer POSTGRES;
    static final GenericContainer<?> MINIO;

    static {
        POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine3.21")
                .withDatabaseName("emenu_assets")
                .withUsername("emenu_assets")
                .withPassword("emenu_assets");
        MINIO = new GenericContainer<>(
                        DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z"))
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
        try {
            POSTGRES.start();
            MINIO.start();
        } catch (RuntimeException exception) {
            MINIO.stop();
            POSTGRES.stop();
            throw exception;
        }
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.assets.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("app.assets.storage.region", () -> "us-east-1");
        registry.add("app.assets.storage.bucket", () -> BUCKET);
        registry.add("app.assets.storage.access-key", () -> ACCESS_KEY);
        registry.add("app.assets.storage.secret-key", () -> SECRET_KEY);
        registry.add("app.assets.storage.path-style-access", () -> true);
        registry.add("app.assets.cdn-base-url", () -> "https://cdn.example.test");
    }

    @Autowired
    private TenantAssetService assetService;

    @Autowired
    private TenantAssetProvisioningService provisioningService;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    void createPrivateBucket() {
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    void stopInfrastructure() {
        MINIO.stop();
        POSTGRES.stop();
    }

    @BeforeEach
    void resetState() {
        s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build())
                .contents()
                .forEach(object -> s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(object.key())
                        .build()));
        jdbcTemplate.update("DELETE FROM menu_items");
        jdbcTemplate.update("DELETE FROM menu_categories");
        jdbcTemplate.update("DELETE FROM menu_versions");
        jdbcTemplate.update("DELETE FROM restaurants");
        jdbcTemplate.update("DELETE FROM tenant_branding");
        jdbcTemplate.update("DELETE FROM tenant_assets");
        jdbcTemplate.update("DELETE FROM tenants");
        insertTenant(TENANT_A, "tenant-a");
        insertTenant(TENANT_B, "tenant-b");
    }

    @Test
    void storesImmutableContentAndReturnsTheSameRecordForTheSameImport() {
        TenantAssetImportRequest request = pngLogo();

        TenantAsset first = assetService.importAsset(TENANT_A, request);
        TenantAsset replay = assetService.importAsset(TENANT_A, request);

        String key = expectedKey(TENANT_A, AssetKind.LOGO, first.sha256(), "png");
        var head = s3Client.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());

        assertThat(replay).isEqualTo(first);
        assertThat(first.publicUrl()).hasToString(
                "https://cdn.example.test/" + key.replaceFirst("^tenants/", "assets/"));
        assertThat(first.publicUrl().getPath()).doesNotContain("/tenants/");
        assertThat(first.publicUrl().getUserInfo()).isNull();
        assertThat(head.contentType()).isEqualTo("image/png");
        assertThat(head.cacheControl()).isEqualTo(S3AssetObjectStorage.IMMUTABLE_CACHE_CONTROL);
        assertThat(head.metadata()).containsEntry("sha256", first.sha256());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tenant_assets WHERE tenant_id = ? AND asset_type = ?",
                        Long.class,
                        TENANT_A.value(),
                        AssetKind.LOGO.storageName()))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM tenant_assets WHERE tenant_id = ? AND id = ?",
                        String.class,
                        TENANT_A.value(),
                        first.id().value()))
                .isEqualTo("active");
        assertThat(objectKeys()).containsExactly(key);
    }

    @Test
    void concurrentIdenticalImportsConvergeOnOneRecordAndOneObject() throws Exception {
        TenantAssetImportRequest request = pngLogo();
        int workers = 4;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<TenantAsset>> calls = java.util.stream.IntStream.range(0, workers)
                .mapToObj(ignored -> (Callable<TenantAsset>) () -> {
                    start.await();
                    return assetService.importAsset(TENANT_A, request);
                })
                .toList();

        List<TenantAsset> results;
        try (var executor = Executors.newFixedThreadPool(workers)) {
            var futures = calls.stream().map(executor::submit).toList();
            start.countDown();
            results = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        }

        assertThat(results).extracting(TenantAsset::id).containsOnly(results.getFirst().id());
        assertThat(objectKeys()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_assets", Long.class)).isEqualTo(1L);
    }

    @Test
    void tenantScopedLookupAndStorageNeverCrossTenantBoundaries() {
        TenantAsset tenantAAsset = assetService.importAsset(TENANT_A, pngLogo());

        assertThatThrownBy(() -> assetService.getAsset(TENANT_B, tenantAAsset.id()))
                .isInstanceOf(TenantAssetNotFoundException.class);

        TenantAsset tenantBAsset = assetService.importAsset(TENANT_B, pngLogo());
        assertThat(tenantBAsset.id()).isNotEqualTo(tenantAAsset.id());
        assertThat(tenantAAsset.publicUrl().toString()).contains("/assets/" + TENANT_A.value() + "/");
        assertThat(tenantBAsset.publicUrl().toString()).contains("/assets/" + TENANT_B.value() + "/");
        assertThat(tenantAAsset.publicUrl().getPath()).doesNotContain("/tenants/");
        assertThat(tenantBAsset.publicUrl().getPath()).doesNotContain("/tenants/");
        assertThat(objectKeys()).containsExactlyInAnyOrder(
                expectedKey(TENANT_A, AssetKind.LOGO, tenantAAsset.sha256(), "png"),
                expectedKey(TENANT_B, AssetKind.LOGO, tenantBAsset.sha256(), "png"));
    }

    @Test
    void batchLookupOmitsDeletedUnknownAndCrossTenantAssets() {
        TenantAsset active = assetService.importAsset(TENANT_A, pngLogo());
        TenantAsset deleted = assetService.importAsset(
                TENANT_A,
                new TenantAssetImportRequest(
                        AssetKind.MENU_ITEM_IMAGE,
                        "image/png",
                        "png",
                        pngBytes()));
        TenantAsset tenantB = assetService.importAsset(TENANT_B, pngLogo());
        jdbcTemplate.update(
                "UPDATE tenant_assets SET status = 'deleted' WHERE tenant_id = ? AND id = ?",
                TENANT_A.value(),
                deleted.id().value());
        TenantAssetId unknown = new TenantAssetId(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));

        var assets = assetService.getActiveAssets(
                TENANT_A,
                List.of(active.id(), active.id(), deleted.id(), tenantB.id(), unknown));

        assertThat(assets).containsOnlyKeys(active.id());
        assertThat(assets.get(active.id())).isEqualTo(active);
    }

    @Test
    void unknownTenantIsRejectedBeforeCreatingARecordOrObject() {
        TenantId unknown = new TenantId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));

        assertThatThrownBy(() -> assetService.importAsset(unknown, pngLogo()))
                .isInstanceOf(TenantAssetNotFoundException.class);
        assertThat(objectKeys()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_assets", Long.class)).isZero();
    }

    @Test
    void privilegedProvisioningSupportsExactSuspendedOrActiveLifecycleWithoutWeakeningOrdinaryService() {
        suspend(TENANT_A);

        assertThatThrownBy(() -> assetService.importAsset(TENANT_A, pngLogo()))
                .isInstanceOf(TenantAssetNotFoundException.class);

        var suspended = provisioningService.provision(
                TENANT_A,
                pngLogo(),
                RUN_A,
                TenantLifecycle.SUSPENDED);
        var active = provisioningService.provision(
                TENANT_B,
                pngLogo(),
                RUN_A,
                TenantLifecycle.ACTIVE);
        var suspendedReplay = provisioningService.provision(
                TENANT_A,
                pngLogo(),
                RUN_A,
                TenantLifecycle.SUSPENDED);

        assertThat(suspended.disposition()).isEqualTo(TenantAssetProvisioningDisposition.CREATED);
        assertThat(active.disposition()).isEqualTo(TenantAssetProvisioningDisposition.CREATED);
        assertThat(suspendedReplay.disposition()).isEqualTo(TenantAssetProvisioningDisposition.RECOVERED);
        assertThat(suspendedReplay.asset()).isEqualTo(suspended.asset());
        assertThat(provisioningService.inspect(
                        TENANT_A,
                        AssetKind.LOGO,
                        suspended.asset().sha256(),
                        RUN_A,
                        TenantLifecycle.SUSPENDED))
                .contains(suspended.asset());
        assertThat(suspended.provenance().toString()).contains("canonicalObjectKey=<redacted>");
        assertThat(java.util.Arrays.stream(suspended.asset().getClass().getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("publicUrl", "canonicalObjectKey");

        var head = head(suspended.provenance().canonicalObjectKey());
        assertThat(head.metadata())
                .containsEntry(S3AssetObjectStorage.SHA_256_METADATA_KEY, suspended.asset().sha256())
                .containsEntry(S3AssetObjectStorage.RUN_FINGERPRINT_METADATA_KEY, RUN_A.value());
    }

    @Test
    void deterministicObjectRejectsAnotherRunAndCrossTenantCleanup() {
        suspend(TENANT_A);
        var provisioned = provisioningService.provision(
                TENANT_A,
                pngLogo(),
                RUN_A,
                TenantLifecycle.SUSPENDED);

        assertThatThrownBy(() -> provisioningService.provision(
                        TENANT_A,
                        pngLogo(),
                        RUN_B,
                        TenantLifecycle.SUSPENDED))
                .isInstanceOf(TenantAssetProvisioningConflictException.class);
        TenantAssetCleanupProof proof =
                provisioningService.verifyCleanupProvenance(TENANT_A, provisioned.provenance());
        assertThatThrownBy(() -> inTransaction(() -> provisioningService.removeRegistrationForCleanup(
                        TENANT_B,
                        proof)))
                .isInstanceOf(TenantAssetProvisioningConflictException.class);

        assertThat(head(provisioned.provenance().canonicalObjectKey()).metadata())
                .containsEntry(S3AssetObjectStorage.RUN_FINGERPRINT_METADATA_KEY, RUN_A.value());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_assets", Long.class)).isEqualTo(1L);
    }

    @Test
    void retryReconcilesFingerprintOwnedObjectLeftBeforeDatabaseRegistration() {
        suspend(TENANT_A);
        String key = expectedKey(TENANT_A, AssetKind.LOGO, PNG_SHA256, "png");
        putProvisionedObject(key, RUN_A);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_assets", Long.class)).isZero();

        var recovered = provisioningService.provision(
                TENANT_A,
                pngLogo(),
                RUN_A,
                TenantLifecycle.SUSPENDED);

        assertThat(recovered.disposition()).isEqualTo(TenantAssetProvisioningDisposition.RECOVERED);
        assertThat(recovered.provenance().canonicalObjectKey()).isEqualTo(key);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_assets", Long.class)).isEqualTo(1L);
        assertThat(objectKeys()).containsExactly(key);
    }

    @Test
    void cleanupIsDatabaseFirstFingerprintVerifiedAndWorksAfterTenantSkeletonRemoval() {
        suspend(TENANT_A);
        var provisioned = provisioningService.provision(
                TENANT_A,
                pngLogo(),
                RUN_A,
                TenantLifecycle.SUSPENDED);

        TenantAssetCleanupProof proof =
                provisioningService.verifyCleanupProvenance(TENANT_A, provisioned.provenance());
        TenantAssetCleanupCandidate candidate = inTransaction(() ->
                provisioningService.removeRegistrationForCleanup(TENANT_A, proof));

        assertThat(candidate.registrationRemoved()).isTrue();
        assertThat(objectKeys()).containsExactly(provisioned.provenance().canonicalObjectKey());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_assets", Long.class)).isZero();

        TenantAssetProvisioningProvenance foreignRun = new TenantAssetProvisioningProvenance(
                TENANT_A,
                provisioned.asset().kind(),
                provisioned.asset().contentType(),
                provisioned.asset().sizeBytes(),
                provisioned.asset().sha256(),
                provisioned.provenance().canonicalObjectKey(),
                RUN_B);
        assertThatThrownBy(() -> provisioningService.verifyCleanupProvenance(TENANT_A, foreignRun))
                .isInstanceOf(TenantAssetProvisioningConflictException.class);
        TenantAssetCleanupCandidate forgedCandidate = new TenantAssetCleanupCandidate() {
            @Override
            public TenantId tenantId() {
                return TENANT_A;
            }

            @Override
            public AssetKind kind() {
                return AssetKind.LOGO;
            }

            @Override
            public String sha256() {
                return provisioned.asset().sha256();
            }

            @Override
            public boolean registrationRemoved() {
                return true;
            }
        };
        assertThatThrownBy(() -> provisioningService.deleteObjectForCleanup(
                        TENANT_A,
                        forgedCandidate))
                .isInstanceOf(TenantAssetProvisioningConflictException.class);
        assertThat(objectKeys()).containsExactly(provisioned.provenance().canonicalObjectKey());

        jdbcTemplate.update(
                """
                INSERT INTO tenant_assets
                    (id, tenant_id, object_key, asset_type, content_type, size_bytes, sha256, status)
                VALUES (?, ?, ?, 'logo', 'image/png', 9, ?, 'active')
                """,
                UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                TENANT_B.value(),
                provisioned.provenance().canonicalObjectKey(),
                "3".repeat(64));
        assertThatThrownBy(() -> provisioningService.deleteObjectForCleanup(TENANT_A, candidate))
                .isInstanceOf(TenantAssetProvisioningConflictException.class);
        jdbcTemplate.update(
                "DELETE FROM tenant_assets WHERE tenant_id = ? AND object_key = ?",
                TENANT_B.value(),
                provisioned.provenance().canonicalObjectKey());

        jdbcTemplate.update("DELETE FROM tenants WHERE id = ?", TENANT_A.value());
        assertThat(provisioningService.deleteObjectForCleanup(TENANT_A, candidate))
                .isEqualTo(TenantAssetObjectCleanupDisposition.DELETED);
        assertThat(provisioningService.deleteObjectForCleanup(TENANT_A, candidate))
                .isEqualTo(TenantAssetObjectCleanupDisposition.ALREADY_ABSENT);
        assertThat(objectKeys()).isEmpty();
    }

    @Test
    void cleanupCannotRemoveAnActiveTenantAsset() {
        var provisioned = provisioningService.provision(
                TENANT_A,
                pngLogo(),
                RUN_A,
                TenantLifecycle.ACTIVE);

        assertThatThrownBy(() -> provisioningService.verifyCleanupProvenance(
                        TENANT_A,
                        provisioned.provenance()))
                .isInstanceOf(TenantAssetProvisioningConflictException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_assets", Long.class)).isEqualTo(1L);
        assertThat(objectKeys()).containsExactly(provisioned.provenance().canonicalObjectKey());
    }

    @Test
    void finalWriterValidationIsMandatoryDbOnlyAndChecksExactTenantKindAndStatus() {
        suspend(TENANT_A);
        suspend(TENANT_B);
        var category = provisioningService.provision(
                TENANT_A,
                pngAsset(AssetKind.MENU_CATEGORY_IMAGE),
                RUN_A,
                TenantLifecycle.SUSPENDED);
        var item = provisioningService.provision(
                TENANT_A,
                pngAsset(AssetKind.MENU_ITEM_IMAGE),
                RUN_A,
                TenantLifecycle.SUSPENDED);
        Map<TenantAssetId, AssetKind> expected = Map.of(
                category.asset().id(), AssetKind.MENU_CATEGORY_IMAGE,
                item.asset().id(), AssetKind.MENU_ITEM_IMAGE);

        inTransaction(() -> {
            provisioningService.validatePreparedAssetReferences(
                    TENANT_A, expected, TenantLifecycle.SUSPENDED);
            return null;
        });
        assertThatThrownBy(() -> provisioningService.validatePreparedAssetReferences(
                        TENANT_A, expected, TenantLifecycle.SUSPENDED))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> inTransaction(() -> {
                    provisioningService.validatePreparedAssetReferences(
                            TENANT_A,
                            Map.of(category.asset().id(), AssetKind.MENU_ITEM_IMAGE),
                            TenantLifecycle.SUSPENDED);
                    return null;
                }))
                .isInstanceOf(TenantAssetProvisioningConflictException.class);
        assertThatThrownBy(() -> inTransaction(() -> {
                    provisioningService.validatePreparedAssetReferences(
                            TENANT_B,
                            Map.of(category.asset().id(), AssetKind.MENU_CATEGORY_IMAGE),
                            TenantLifecycle.SUSPENDED);
                    return null;
                }))
                .isInstanceOf(TenantAssetProvisioningConflictException.class);

        jdbcTemplate.update(
                "UPDATE tenant_assets SET status = 'pending' WHERE tenant_id = ? AND id = ?",
                TENANT_A.value(),
                item.asset().id().value());
        assertThatThrownBy(() -> inTransaction(() -> {
                    provisioningService.validatePreparedAssetReferences(
                            TENANT_A, expected, TenantLifecycle.SUSPENDED);
                    return null;
                }))
                .isInstanceOf(TenantAssetProvisioningConflictException.class);
    }

    @Test
    void preparedAssetInspectionIsStandaloneDbOnlyZeroWriteAndReturnsActualSha() {
        suspend(TENANT_B);
        var category = provisioningService.provision(
                TENANT_A,
                pngAsset(AssetKind.MENU_CATEGORY_IMAGE),
                RUN_A,
                TenantLifecycle.ACTIVE);
        var item = provisioningService.provision(
                TENANT_A,
                pngAsset(AssetKind.MENU_ITEM_IMAGE),
                RUN_A,
                TenantLifecycle.ACTIVE);
        Map<TenantAssetId, AssetKind> expected = Map.of(
                category.asset().id(), AssetKind.MENU_CATEGORY_IMAGE,
                item.asset().id(), AssetKind.MENU_ITEM_IMAGE);
        assertThat(provisioningService.inspectPreparedAssetReferences(
                        TENANT_A,
                        expected,
                        TenantLifecycle.ACTIVE))
                .containsOnlyKeys(category.asset().id(), item.asset().id());
        suspend(TENANT_A);
        long rowCountBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tenant_assets WHERE tenant_id = ?",
                Long.class,
                TENANT_A.value());
        for (var provisioned : List.of(category, item)) {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(BUCKET)
                    .key(provisioned.provenance().canonicalObjectKey())
                    .build());
        }

        var inspected = provisioningService.inspectPreparedAssetReferences(
                TENANT_A,
                expected,
                TenantLifecycle.SUSPENDED);

        assertThat(inspected).containsOnlyKeys(category.asset().id(), item.asset().id());
        assertThat(inspected.get(category.asset().id()).kind()).isEqualTo(AssetKind.MENU_CATEGORY_IMAGE);
        assertThat(inspected.get(category.asset().id()).sha256()).isEqualTo(category.asset().sha256());
        assertThat(inspected.get(item.asset().id()).kind()).isEqualTo(AssetKind.MENU_ITEM_IMAGE);
        assertThat(inspected.get(item.asset().id()).sha256()).isEqualTo(item.asset().sha256());
        assertThat(inTransaction(() -> provisioningService.inspectPreparedAssetReferences(
                        TENANT_A,
                        expected,
                        TenantLifecycle.SUSPENDED)))
                .isEqualTo(inspected);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tenant_assets WHERE tenant_id = ?",
                        Long.class,
                        TENANT_A.value()))
                .isEqualTo(rowCountBefore);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT status FROM tenant_assets WHERE tenant_id = ? ORDER BY id",
                        String.class,
                        TENANT_A.value()))
                .containsOnly("active");
        assertThatThrownBy(() -> provisioningService.inspectPreparedAssetReferences(
                        TENANT_A,
                        expected,
                        TenantLifecycle.ACTIVE))
                .isInstanceOf(TenantAssetProvisioningConflictException.class);

        assertThatThrownBy(() -> provisioningService.inspectPreparedAssetReferences(
                        TENANT_A,
                        Map.of(category.asset().id(), AssetKind.MENU_ITEM_IMAGE),
                        TenantLifecycle.SUSPENDED))
                .isInstanceOf(TenantAssetProvisioningConflictException.class);
        assertThatThrownBy(() -> provisioningService.inspectPreparedAssetReferences(
                        TENANT_B,
                        Map.of(category.asset().id(), AssetKind.MENU_CATEGORY_IMAGE),
                        TenantLifecycle.SUSPENDED))
                .isInstanceOf(TenantAssetProvisioningConflictException.class);
        assertThatThrownBy(() -> provisioningService.inspectPreparedAssetReferences(
                        TENANT_A,
                        Map.of(new TenantAssetId(UUID.randomUUID()), AssetKind.MENU_CATEGORY_IMAGE),
                        TenantLifecycle.SUSPENDED))
                .isInstanceOf(TenantAssetProvisioningConflictException.class);

        jdbcTemplate.update(
                "UPDATE tenant_assets SET status = 'pending' WHERE tenant_id = ? AND id = ?",
                TENANT_A.value(),
                item.asset().id().value());
        assertThatThrownBy(() -> provisioningService.inspectPreparedAssetReferences(
                        TENANT_A,
                        expected,
                        TenantLifecycle.SUSPENDED))
                .isInstanceOf(TenantAssetProvisioningConflictException.class);
        jdbcTemplate.update(
                "UPDATE tenant_assets SET status = 'deleted' WHERE tenant_id = ? AND id = ?",
                TENANT_A.value(),
                item.asset().id().value());
        assertThatThrownBy(() -> provisioningService.inspectPreparedAssetReferences(
                        TENANT_A,
                        expected,
                        TenantLifecycle.SUSPENDED))
                .isInstanceOf(TenantAssetProvisioningConflictException.class);
    }

    @Test
    void cleanupReferenceScanIncludesScalarAndBrandingJsonAssetIds() {
        suspend(TENANT_A);
        var logo = provisioningService.provision(
                TENANT_A, pngAsset(AssetKind.LOGO), RUN_A, TenantLifecycle.SUSPENDED);
        var hero = provisioningService.provision(
                TENANT_A, pngAsset(AssetKind.HERO), RUN_A, TenantLifecycle.SUSPENDED);
        var openGraph = provisioningService.provision(
                TENANT_A, pngAsset(AssetKind.OPEN_GRAPH), RUN_A, TenantLifecycle.SUSPENDED);
        jdbcTemplate.update(
                """
                INSERT INTO tenant_branding
                    (tenant_id, display_name, business_type, logo_asset_id,
                     design_tokens, localized_texts, hero, seo_metadata)
                VALUES (?, 'Tenant A', 'restaurant', ?, '{}'::jsonb, '[]'::jsonb,
                        jsonb_build_object('imageAssetId', ?::text),
                        jsonb_build_object('openGraphAssetId', ?::text))
                """,
                TENANT_A.value(),
                logo.asset().id().value(),
                hero.asset().id().value(),
                openGraph.asset().id().value());

        for (var provisioned : List.of(logo, hero, openGraph)) {
            TenantAssetCleanupProof proof =
                    provisioningService.verifyCleanupProvenance(TENANT_A, provisioned.provenance());
            assertThatThrownBy(() -> inTransaction(() ->
                            provisioningService.removeRegistrationForCleanup(TENANT_A, proof)))
                    .isInstanceOf(TenantAssetProvisioningConflictException.class);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_assets", Long.class)).isEqualTo(3L);
    }

    @Test
    void cleanupReliesOnMenuForeignKeyAndRedactsIntegrityFailure() {
        suspend(TENANT_A);
        var categoryAsset = provisioningService.provision(
                TENANT_A,
                pngAsset(AssetKind.MENU_CATEGORY_IMAGE),
                RUN_A,
                TenantLifecycle.SUSPENDED);
        UUID restaurantId = UUID.randomUUID();
        UUID menuVersionId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO restaurants (id, tenant_id, slug, display_name, status, is_default)
                VALUES (?, ?, 'asset-fk', 'Asset FK', 'active', true)
                """,
                restaurantId,
                TENANT_A.value());
        jdbcTemplate.update(
                """
                INSERT INTO menu_versions (id, tenant_id, restaurant_id, version, status)
                VALUES (?, ?, ?, 1, 'draft')
                """,
                menuVersionId,
                TENANT_A.value(),
                restaurantId);
        jdbcTemplate.update(
                """
                INSERT INTO menu_categories
                    (id, tenant_id, menu_version_id, public_id, name, image_asset_id, sort_order)
                VALUES (?, ?, ?, 'asset-fk', 'Asset FK', ?, 0)
                """,
                UUID.randomUUID(),
                TENANT_A.value(),
                menuVersionId,
                categoryAsset.asset().id().value());
        TenantAssetCleanupProof proof = provisioningService.verifyCleanupProvenance(
                TENANT_A,
                categoryAsset.provenance());

        assertThatThrownBy(() -> inTransaction(() ->
                        provisioningService.removeRegistrationForCleanup(TENANT_A, proof)))
                .isInstanceOf(TenantAssetProvisioningConflictException.class)
                .hasMessage("Referenced asset cannot be cleaned up")
                .hasNoCause();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tenant_assets WHERE tenant_id = ? AND id = ?",
                        Long.class,
                        TENANT_A.value(),
                        categoryAsset.asset().id().value()))
                .isEqualTo(1L);
    }

    private void insertTenant(TenantId tenantId, String slug) {
        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, slug, status, locale, currency, timezone)
                VALUES (?, ?, 'active', 'en', 'GBP', 'Europe/London')
                """,
                tenantId.value(),
                slug);
    }

    private void suspend(TenantId tenantId) {
        jdbcTemplate.update(
                "UPDATE tenants SET status = 'suspended', updated_at = now() WHERE id = ?",
                tenantId.value());
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(ignored -> action.get());
    }

    private software.amazon.awssdk.services.s3.model.HeadObjectResponse head(String key) {
        return s3Client.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
    }

    private void putProvisionedObject(String key, ProvisioningRunFingerprint fingerprint) {
        byte[] content = pngBytes();
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(key)
                        .contentType("image/png")
                        .contentLength((long) content.length)
                        .cacheControl(S3AssetObjectStorage.IMMUTABLE_CACHE_CONTROL)
                        .metadata(java.util.Map.of(
                                S3AssetObjectStorage.SHA_256_METADATA_KEY, PNG_SHA256,
                                S3AssetObjectStorage.RUN_FINGERPRINT_METADATA_KEY, fingerprint.value()))
                        .build(),
                RequestBody.fromBytes(content));
    }

    private Set<String> objectKeys() {
        return s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build())
                .contents()
                .stream()
                .map(object -> object.key())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static TenantAssetImportRequest pngLogo() {
        return pngAsset(AssetKind.LOGO);
    }

    private static TenantAssetImportRequest pngAsset(AssetKind kind) {
        return new TenantAssetImportRequest(
                kind,
                "image/png",
                "png",
                pngBytes());
    }

    private static byte[] pngBytes() {
        return new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01};
    }

    private static String expectedKey(TenantId tenantId, AssetKind kind, String sha256, String extension) {
        return "tenants/%s/%s/%s.%s".formatted(tenantId.value(), kind.storageName(), sha256, extension);
    }
}
