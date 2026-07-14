package com.salamu.menu.dataimport.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.salamu.menu.asset.api.AssetImportReceipt;
import com.salamu.menu.asset.api.AssetImporter;
import com.salamu.menu.asset.api.AssetKind;
import com.salamu.menu.dataimport.api.ApprovedDatasetImporter;
import com.salamu.menu.dataimport.api.DataImportConflictException;
import com.salamu.menu.dataimport.api.DataImportRequest;
import com.salamu.menu.dataimport.api.StambulDataImportApplication;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.worker.enabled=false",
            "app.data-import.enabled=true",
            "app.security.request-authority.allowed-hosts=127.0.0.1",
            "app.security.cors.allowed-origins=https://stambul.localhost",
            "app.security.trusted-proxies.enabled=false",
            "app.external.public-base-url=https://stambul.localhost",
            "app.external.asset-base-url=https://cdn.stambul.localhost",
            "management.tracing.export.otlp.enabled=false",
            "management.otlp.metrics.export.enabled=false",
            "management.logging.export.otlp.enabled=false"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StambulDataImportPostgreSqlIntegrationTests {

    private static final String BUCKET = "stambul-import-test";
    private static final String ACCESS_KEY = "stambul-access";
    private static final String SECRET_KEY = "stambul-secret";
    private static final String LOGO_SHA =
            "90374c21b9cd4c15ef5b861521d0e5bcdc4571c3fae6f4aebd5c3927bfb6ee9b";
    private static final String LOGO_KEY = "assets/logo/" + LOGO_SHA + ".png";
    private static final Path ROOT = repositoryRoot();
    private static final Path BUNDLE = ROOT.resolve("docs/migration/data/stambul-approved-v1.json");
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine3.21")
            .withDatabaseName("stambul_import")
            .withUsername("stambul_import")
            .withPassword("stambul_import");
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
    private static final S3Client S3;

    static {
        POSTGRES.start();
        MINIO.start();
        S3 = S3Client.builder()
                .endpointOverride(URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        S3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.assets.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("app.assets.storage.region", () -> "us-east-1");
        registry.add("app.assets.storage.bucket", () -> BUCKET);
        registry.add("app.assets.storage.access-key", () -> ACCESS_KEY);
        registry.add("app.assets.storage.secret-key", () -> SECRET_KEY);
        registry.add("app.assets.storage.path-style-access", () -> true);
        registry.add("app.assets.cdn-base-url", () -> "https://cdn.stambul.localhost");
    }

    @Autowired
    private ApprovedDatasetImporter importer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @LocalServerPort
    private int port;

    @BeforeEach
    void resetDatabaseAndObjectStore() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_stambul_item ON menu_items");
        jdbc.execute("DROP FUNCTION IF EXISTS reject_stambul_item()");
        jdbc.execute("""
                TRUNCATE TABLE
                    notification_deliveries, outbox_events, order_status_history, order_items,
                    orders, order_counter, notification_destinations, menu_modifier_options,
                    menu_modifier_groups, menu_items, menu_categories, menu_versions,
                    restaurants, site_features, site_profile, site_branding,
                    site_configuration, assets
                CASCADE
                """);
        for (var object : S3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build()).contents()) {
            S3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(object.key()).build());
        }
    }

    @AfterAll
    void stopContainers() {
        S3.close();
        MINIO.stop();
        POSTGRES.stop();
    }

    @Test
    void dryRunPerformsZeroDatabaseSchemaAndObjectStoreWrites() {
        long migrationsBefore = count("flyway_schema_history");
        long publicTablesBefore = jdbc.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname = 'public'
                """, Long.class);

        var result = importer.dryRun(DataImportRequest.localTest(BUNDLE, ROOT));

        assertThat(result.outcome()).isEqualTo("READY");
        assertThat(count("flyway_schema_history")).isEqualTo(migrationsBefore);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname = 'public'
                """, Long.class)).isEqualTo(publicTablesBefore);
        assertThat(contentRowCount()).isZero();
        assertThat(S3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build()).keyCount()).isZero();
    }

    @Test
    void dedicatedCommandContextHasEveryJdbcDependencyAndNoRuntimeWebWorkerOrJpaGraph() {
        try (var context = new SpringApplicationBuilder(StambulDataImportApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.main.banner-mode=off",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.sql.init.mode=never",
                        "--app.data-import.enabled=true",
                        "--app.assets.storage.endpoint=http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000),
                        "--app.assets.storage.region=us-east-1",
                        "--app.assets.storage.bucket=" + BUCKET,
                        "--app.assets.storage.access-key=" + ACCESS_KEY,
                        "--app.assets.storage.secret-key=" + SECRET_KEY,
                        "--app.assets.storage.path-style-access=true",
                        "--app.assets.cdn-base-url=https://cdn.stambul.localhost")) {
            assertThat(context.getBean(ApprovedDatasetImporter.class)).isNotNull();
            assertThat(context.getBean(JdbcTemplate.class)).isNotNull();
            assertThat(context.getBean(JdbcClient.class)).isNotNull();
            assertThat(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).isNotNull();
            assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
            assertThat(context.containsBean("entityManagerFactory")).isFalse();
            assertThat(context.containsBean("flyway")).isFalse();
            assertThat(context.getBeanDefinitionNames())
                    .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("controller"))
                    .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("worker"));
        }
    }

    @Test
    void applyIsTransactionalIdempotentAndPublicConfigAndMenuMatchTheApprovedBundle() throws Exception {
        var created = importer.apply(DataImportRequest.localTest(BUNDLE, ROOT));
        var unchanged = importer.apply(DataImportRequest.localTest(BUNDLE, ROOT));

        assertThat(created.outcome()).isEqualTo("CREATED");
        assertThat(unchanged.outcome()).isEqualTo("UNCHANGED");
        assertThat(created.menuRepresentationHash())
                .isEqualTo("24071fa64801b7e8d2695955c543839e603c9fd45633de242bd79e34b30e710b");
        assertThat(unchanged.configurationRepresentationHash())
                .isEqualTo(created.configurationRepresentationHash());
        assertThat(unchanged.menuRepresentationHash()).isEqualTo(created.menuRepresentationHash());
        assertThat(unchanged.bundleSha256()).isEqualTo(StambulBundleLoader.APPROVED_BUNDLE_SHA256);
        assertThat(unchanged.assetId()).isEqualTo(UUID.fromString("41eec9b7-b68e-5c82-ac50-a988df90b415"));
        assertThat(unchanged.restaurantId()).isEqualTo(UUID.fromString("d4579042-8239-57df-b716-c27d3a6c213a"));
        assertThat(unchanged.menuVersionId()).isEqualTo(UUID.fromString("db0d6a46-9145-55c7-af56-2795b510fa19"));
        assertThat(unchanged.categoryCount()).isEqualTo(4);
        assertThat(unchanged.itemCount()).isEqualTo(23);
        assertThat(count("notification_destinations")).isZero();
        assertThat(count("orders")).isZero();
        assertThat(count("outbox_events")).isZero();
        assertThat(count("notification_deliveries")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM menu_items WHERE name = 'Пицца    4 сыра'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM restaurants
                 WHERE external_key <> 'stambul' OR slug <> 'stambul'
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assets WHERE sha256 <> ?", Long.class, LOGO_SHA)).isZero();

        var head = S3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(LOGO_KEY).build());
        assertThat(head.contentLength()).isEqualTo(43728);
        assertThat(head.contentType()).isEqualTo("image/png");
        assertThat(head.metadata()).containsEntry("sha256", LOGO_SHA);
        assertThat(S3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build()).keyCount()).isEqualTo(1);

        HttpResponse<String> config = get("/api/v1/public/config");
        HttpResponse<String> menu = get("/api/v1/public/menu");
        assertThat(config.statusCode()).isEqualTo(200);
        assertThat(menu.statusCode()).isEqualTo(200);
        assertThat(config.headers().firstValue("ETag")).contains(
                '"' + "config-" + created.configurationRepresentationHash() + '"');
        assertThat(menu.headers().firstValue("ETag")).contains(
                '"' + "menu-v1-" + created.menuRepresentationHash() + '"');

        JsonNode configBody = objectMapper.readTree(config.body());
        assertThat(configBody.get("restaurantId").asString()).isEqualTo(created.restaurantId().toString());
        assertThat(configBody.get("locale").asString()).isEqualTo("ru-RU");
        assertThat(configBody.get("currency").asString()).isEqualTo("RUB");
        assertThat(configBody.get("timezone").asString()).isEqualTo("Europe/Moscow");
        assertThat(configBody.at("/seo/canonicalUrl").asString()).isEqualTo("https://stambul.localhost");
        assertThat(configBody.at("/branding/logoUrl").asString()).isEqualTo(
                "https://cdn.stambul.localhost/" + LOGO_KEY);
        assertThat(configBody.at("/branding/logoAlt").asString())
                .isEqualTo("Логотип ресторан-кафе «У Ризвана»");
        assertThat(configBody.at("/branding/designTokens/mutedTextColor").asString()).isEqualTo("#7C6A64");
        assertThat(configBody.at("/branding/designTokens/fontFamily").asString())
                .isEqualTo("Georgia, Times New Roman, serif");
        assertThat(configBody.at("/features/geoLocation").booleanValue()).isFalse();
        assertThat(configBody.at("/features/whatsappFallback").booleanValue()).isTrue();
        assertThat(configBody.at("/limits/delayedOrderConfirmationMinutes").intValue()).isEqualTo(15);

        JsonNode menuBody = objectMapper.readTree(menu.body());
        assertThat(menuBody.get("version").longValue()).isEqualTo(1);
        assertThat(menuBody.get("currency").asString()).isEqualTo("RUB");
        assertThat(menuBody.get("categories")).hasSize(4);
        assertThat(menuBody.get("items")).hasSize(23);
        assertThat(strings(menuBody.get("categories"), "categoryId"))
                .containsExactly("rolls", "pizza", "sides", "burgers");
        JsonNode fourCheese = find(menuBody.get("items"), "menuItemId", "pizza-four-cheese");
        assertThat(fourCheese.get("name").asString()).isEqualTo("Пицца    4 сыра");
        assertThat(fourCheese.get("priceMinor").longValue()).isEqualTo(35000);
        assertThat(fourCheese.get("modifierGroups")).isEmpty();
    }

    @Test
    void concurrentIdenticalApplyCreatesOneExactGraphAndOneImmutableObject() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return importer.apply(DataImportRequest.localTest(BUNDLE, ROOT));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return importer.apply(DataImportRequest.localTest(BUNDLE, ROOT));
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(
                            first.get(30, TimeUnit.SECONDS).outcome(),
                            second.get(30, TimeUnit.SECONDS).outcome()))
                    .containsExactlyInAnyOrder("CREATED", "UNCHANGED");
        }

        assertThat(count("site_configuration")).isEqualTo(1);
        assertThat(count("assets")).isEqualTo(1);
        assertThat(count("site_branding")).isEqualTo(1);
        assertThat(count("site_profile")).isEqualTo(1);
        assertThat(count("site_features")).isEqualTo(13);
        assertThat(count("restaurants")).isEqualTo(1);
        assertThat(count("menu_versions")).isEqualTo(1);
        assertThat(count("menu_categories")).isEqualTo(4);
        assertThat(count("menu_items")).isEqualTo(23);
        assertThat(count("notification_destinations")).isZero();
        assertThat(count("orders")).isZero();
        assertThat(count("outbox_events")).isZero();
        assertThat(S3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build()).keyCount()).isEqualTo(1);
    }

    @Test
    void changedExistingGraphFailsClosedWithoutAdditionalObjectWrites() {
        importer.apply(DataImportRequest.localTest(BUNDLE, ROOT));
        jdbc.update("UPDATE menu_items SET price_minor = price_minor + 1 WHERE public_id = 'pizza-caesar'");

        assertThatThrownBy(() -> importer.dryRun(DataImportRequest.localTest(BUNDLE, ROOT)))
                .isInstanceOf(DataImportConflictException.class);
        assertThatThrownBy(() -> importer.apply(DataImportRequest.localTest(BUNDLE, ROOT)))
                .isInstanceOf(DataImportConflictException.class);
        assertThat(S3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build()).keyCount()).isEqualTo(1);
    }

    @Test
    void lateDatabaseFailureRollsBackTheCompletePublicGraph() {
        jdbc.execute("""
                CREATE FUNCTION reject_stambul_item() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.public_id = 'pizza-four-cheese' THEN
                        RAISE EXCEPTION 'injected import failure';
                    END IF;
                    RETURN NEW;
                END
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_stambul_item BEFORE INSERT ON menu_items
                FOR EACH ROW EXECUTE FUNCTION reject_stambul_item()
                """);

        assertThatThrownBy(() -> importer.apply(DataImportRequest.localTest(BUNDLE, ROOT)))
                .hasMessageContaining("injected import failure");
        assertThat(contentRowCount()).isZero();
        assertThat(count("orders")).isZero();
        assertThat(count("outbox_events")).isZero();
        assertThat(S3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build()).keyCount()).isEqualTo(1);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/json")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private long contentRowCount() {
        return List.of(
                        "site_configuration", "assets", "site_branding", "site_profile",
                        "site_features", "restaurants", "menu_versions", "menu_categories",
                        "menu_items", "menu_modifier_groups", "menu_modifier_options")
                .stream()
                .mapToLong(this::count)
                .sum();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static List<String> strings(JsonNode array, String field) {
        var result = new java.util.ArrayList<String>();
        array.forEach(node -> result.add(node.get(field).asString()));
        return result;
    }

    private static JsonNode find(JsonNode array, String field, String expected) {
        for (JsonNode node : array) {
            if (expected.equals(node.get(field).asString())) {
                return node;
            }
        }
        throw new AssertionError("Expected fixture value was not found: " + expected);
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("AGENTS.md"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("Repository root was not found");
        }
        return candidate;
    }
}
