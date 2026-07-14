package com.salamu.menu.asset.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.salamu.menu.asset.api.AssetId;
import com.salamu.menu.asset.api.AssetUnavailableException;
import com.salamu.menu.asset.api.PublicAsset;
import com.salamu.menu.asset.internal.application.AssetCatalogService;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcAssetMetadataRepositoryPostgreSqlTests {

    private static final String INSERT_ASSET = """
            INSERT INTO assets (
                id, object_key, asset_type, content_type, size_bytes, sha256, status, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private PostgreSQLContainer postgres;
    private JdbcTemplate setupJdbc;
    private AtomicInteger assetSelectCount;
    private AssetCatalogService catalog;

    @BeforeAll
    void startPostgreSqlAndMigrateToFinalSchema() {
        postgres = new PostgreSQLContainer("postgres:17.5-alpine3.21")
                .withDatabaseName("asset_repository")
                .withUsername("asset_repository")
                .withPassword("asset_repository");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        setupJdbc = new JdbcTemplate(dataSource);
        assetSelectCount = new AtomicInteger();
        DataSource countingDataSource = countingDataSource(dataSource, assetSelectCount);
        JdbcAssetMetadataRepository repository = new JdbcAssetMetadataRepository(
                new JdbcTemplate(countingDataSource));
        catalog = new AssetCatalogService(
                repository,
                new CdnAssetPublicUrlResolver(java.net.URI.create("https://cdn.example.test")));
    }

    @AfterAll
    void stopPostgreSql() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void resetAssets() {
        setupJdbc.update("DELETE FROM assets");
        assetSelectCount.set(0);
    }

    @Test
    void emptyBatchReturnsImmutableEmptyMapWithZeroSql() {
        Map<AssetId, PublicAsset> result = catalog.requireActive(List.of());

        assertThat(result).isEmpty();
        assertThatThrownBy(() -> result.put(assetId(1), null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(assetSelectCount).hasValue(0);
    }

    @Test
    void deduplicatesFiveHundredIdsUsesOneSelectAndPreservesInputOrder() {
        List<AssetId> databaseOrder = assetIds(500);
        insert(databaseOrder, "active");
        List<AssetId> requestedOrder = new ArrayList<>(databaseOrder);
        Collections.reverse(requestedOrder);
        requestedOrder.addAll(requestedOrder.subList(0, 20));

        Map<AssetId, PublicAsset> result = catalog.requireActive(requestedOrder);

        assertThat(result).hasSize(500);
        assertThat(result.keySet()).containsExactlyElementsOf(requestedOrder.subList(0, 500));
        assertThatThrownBy(() -> result.remove(requestedOrder.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(assetSelectCount).hasValue(1);
    }

    @Test
    void missingAndDeletedRowsFailClosedAfterOneSelect() {
        AssetId deleted = assetId(1);
        insert(List.of(deleted), "deleted");
        AssetId missing = assetId(2);

        assertThatThrownBy(() -> catalog.requireActive(List.of(deleted, missing)))
                .isInstanceOf(AssetUnavailableException.class)
                .hasMessage("Required asset is unavailable")
                .hasMessageNotContaining(deleted.value().toString())
                .hasMessageNotContaining(missing.value().toString());
        assertThat(assetSelectCount).hasValue(1);
    }

    private void insert(List<AssetId> ids, String status) {
        setupJdbc.batchUpdate(INSERT_ASSET, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                AssetId id = ids.get(index);
                String sha256 = sha256(id);
                statement.setObject(1, id.value());
                statement.setString(2, "assets/logo/" + sha256 + ".png");
                statement.setString(3, "logo");
                statement.setString(4, "image/png");
                statement.setLong(5, 8L);
                statement.setString(6, sha256);
                statement.setString(7, status);
                statement.setTimestamp(8, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
            }

            @Override
            public int getBatchSize() {
                return ids.size();
            }
        });
    }

    private static DataSource countingDataSource(DataSource delegate, AtomicInteger assetSelectCount) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if ((method.getName().equals("getConnection")) && result instanceof Connection connection) {
                            return countingConnection(connection, assetSelectCount);
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private static Connection countingConnection(Connection delegate, AtomicInteger assetSelectCount) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("prepareStatement")
                            && arguments != null
                            && arguments.length > 0
                            && arguments[0] instanceof String sql
                            && sql.contains("FROM assets")) {
                        assetSelectCount.incrementAndGet();
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private static List<AssetId> assetIds(int count) {
        List<AssetId> ids = new ArrayList<>(count);
        for (int number = 1; number <= count; number++) {
            ids.add(assetId(number));
        }
        return ids;
    }

    private static AssetId assetId(int number) {
        return new AssetId(new UUID(0, number));
    }

    private static String sha256(AssetId id) {
        return "%064x".formatted(id.value().getLeastSignificantBits());
    }
}
