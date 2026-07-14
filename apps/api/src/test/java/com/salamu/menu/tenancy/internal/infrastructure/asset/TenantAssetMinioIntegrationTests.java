package com.salamu.menu.tenancy.internal.infrastructure.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.TenantAsset;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantAssetImportRequest;
import com.salamu.menu.tenancy.api.TenantAssetNotFoundException;
import com.salamu.menu.tenancy.api.TenantAssetService;
import com.salamu.menu.tenancy.api.TenantId;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

@ActiveProfiles("test")
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
        registry.add("app.assets.cdn-base-url", () -> "https://cdn.example.test/assets");
    }

    @Autowired
    private TenantAssetService assetService;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        assertThat(first.publicUrl()).hasToString("https://cdn.example.test/assets/" + key);
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
        assertThat(tenantAAsset.publicUrl().toString()).contains("/tenants/" + TENANT_A.value() + "/");
        assertThat(tenantBAsset.publicUrl().toString()).contains("/tenants/" + TENANT_B.value() + "/");
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

    private void insertTenant(TenantId tenantId, String slug) {
        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, slug, status, locale, currency, timezone)
                VALUES (?, ?, 'active', 'en', 'GBP', 'Europe/London')
                """,
                tenantId.value(),
                slug);
    }

    private Set<String> objectKeys() {
        return s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build())
                .contents()
                .stream()
                .map(object -> object.key())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static TenantAssetImportRequest pngLogo() {
        return new TenantAssetImportRequest(
                AssetKind.LOGO,
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
