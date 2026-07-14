package com.salamu.menu.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class FlywaySchemaIntegrationTests {

    private static final String MIGRATION_SCHEMA = "migration_sequence";
    private static final String CONSTRAINT_SCHEMA = "constraint_test";

    @Test
    void migratesAnEmptyDatabaseSequentiallyAndValidatesEveryVersion() throws SQLException {
        var expectedTables = Map.of(
                1, "tenants",
                2, "menu_items",
                3, "orders",
                4, "outbox_events",
                5, "legacy_import_runs",
                6, "sc_site_configuration");

        for (int version = 1; version <= 6; version++) {
            var flyway = flyway(MIGRATION_SCHEMA, MigrationVersion.fromVersion(Integer.toString(version)));
            flyway.migrate();

            var validation = flyway.validateWithResult();
            assertThat(validation.validationSuccessful)
                    .as("Flyway validation after V%s", version)
                    .isTrue();
            assertThat(currentVersion(MIGRATION_SCHEMA)).isEqualTo(Integer.toString(version));
            assertThat(tableExists(MIGRATION_SCHEMA, expectedTables.get(version))).isTrue();
        }

        var finalValidation = flyway(MIGRATION_SCHEMA, null).validateWithResult();
        assertThat(finalValidation.validationSuccessful).isTrue();
    }

    @Test
    void enforcesTenantIsolationAndBusinessUniquenessInPostgreSql() throws SQLException {
        flyway(CONSTRAINT_SCHEMA, null).migrate();

        try (var connection = connection(CONSTRAINT_SCHEMA)) {
            insertTenantsAndRestaurants(connection);
            assertDomainLifecycleConstraints(connection);
            insertPublishedMenuFixtures(connection);
            assertPublishedMenuAndCrossTenantConstraints(connection);
            insertAssets(connection);
            assertAssetIsolationAndContentUniqueness(connection);
            insertOrder(connection);
            assertOrderIsolationAndUniqueness(connection);
        }
    }

    private static Flyway flyway(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(
                        PostgreSqlTestContainer.INSTANCE.getJdbcUrl(),
                        PostgreSqlTestContainer.INSTANCE.getUsername(),
                        PostgreSqlTestContainer.INSTANCE.getPassword())
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
                PostgreSqlTestContainer.INSTANCE.getJdbcUrl(),
                PostgreSqlTestContainer.INSTANCE.getUsername(),
                PostgreSqlTestContainer.INSTANCE.getPassword());
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

    private static boolean tableExists(String schema, String table) throws SQLException {
        try (var connection = connection(schema);
                var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, schema + "." + table);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    private static void insertTenantsAndRestaurants(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO tenants (id, slug, status, locale, currency, timezone)
                VALUES
                    ('10000000-0000-4000-8000-000000000001', 'tenant-a', 'active', 'ru-RU', 'RUB', 'Europe/Moscow'),
                    ('20000000-0000-4000-8000-000000000002', 'tenant-b', 'active', 'ru-RU', 'RUB', 'Europe/Moscow')
                """);
        execute(connection, """
                INSERT INTO restaurants (id, tenant_id, slug, display_name, status, is_default)
                VALUES
                    ('11000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001',
                     'main', 'Tenant A restaurant', 'active', true),
                    ('22000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000002',
                     'main', 'Tenant B restaurant', 'active', true)
                """);
    }

    private static void assertDomainLifecycleConstraints(Connection connection) throws SQLException {
        expectSqlState(connection, "23514", """
                INSERT INTO tenant_domains (
                    id, tenant_id, hostname, domain_type, status, canonical
                ) VALUES (
                    '10100000-0000-4000-8000-000000000001',
                    '10000000-0000-4000-8000-000000000001',
                    'pending.example.test', 'custom', 'pending', true
                )
                """);
        expectSqlState(connection, "23514", """
                INSERT INTO tenant_domains (
                    id, tenant_id, hostname, domain_type, status, canonical
                ) VALUES (
                    '10200000-0000-4000-8000-000000000001',
                    '10000000-0000-4000-8000-000000000001',
                    'unverified.example.test', 'custom', 'active', false
                )
                """);
        execute(connection, """
                INSERT INTO tenant_domains (
                    id, tenant_id, hostname, domain_type, status, canonical, verified_at
                ) VALUES (
                    '10300000-0000-4000-8000-000000000001',
                    '10000000-0000-4000-8000-000000000001',
                    'tenant-a.example.test', 'custom', 'active', true, now()
                )
                """);
        expectSqlState(connection, "23505", """
                INSERT INTO tenant_domains (
                    id, tenant_id, hostname, domain_type, status, canonical, verified_at
                ) VALUES (
                    '10400000-0000-4000-8000-000000000001',
                    '10000000-0000-4000-8000-000000000001',
                    'second.tenant-a.example.test', 'custom', 'active', true, now()
                )
                """);
        expectSqlState(connection, "23514", """
                INSERT INTO tenant_profiles (tenant_id, latitude)
                VALUES ('10000000-0000-4000-8000-000000000001', 55.755826)
                """);
    }

    private static void insertPublishedMenuFixtures(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO menu_versions (id, tenant_id, restaurant_id, version, status, published_at)
                VALUES
                    ('12000000-0000-4000-8000-000000000001',
                     '10000000-0000-4000-8000-000000000001',
                     '11000000-0000-4000-8000-000000000001', 1, 'published', now()),
                    ('23000000-0000-4000-8000-000000000002',
                     '20000000-0000-4000-8000-000000000002',
                     '22000000-0000-4000-8000-000000000002', 1, 'published', now())
                """);
        execute(connection, """
                INSERT INTO menu_categories (
                    id, tenant_id, menu_version_id, public_id, name, sort_order
                ) VALUES (
                    '13000000-0000-4000-8000-000000000001',
                    '10000000-0000-4000-8000-000000000001',
                    '12000000-0000-4000-8000-000000000001',
                    'pizza', 'Pizza', 0
                )
                """);
    }

    private static void assertPublishedMenuAndCrossTenantConstraints(Connection connection) throws SQLException {
        expectSqlState(connection, "23505", """
                INSERT INTO menu_versions (id, tenant_id, restaurant_id, version, status, published_at)
                VALUES (
                    '12100000-0000-4000-8000-000000000001',
                    '10000000-0000-4000-8000-000000000001',
                    '11000000-0000-4000-8000-000000000001', 2, 'published', now()
                )
                """);
        expectSqlState(connection, "23503", """
                INSERT INTO menu_versions (id, tenant_id, restaurant_id, version, status)
                VALUES (
                    '23100000-0000-4000-8000-000000000002',
                    '10000000-0000-4000-8000-000000000001',
                    '22000000-0000-4000-8000-000000000002', 2, 'draft'
                )
                """);
    }

    private static void insertAssets(Connection connection) throws SQLException {
        var digest = "a".repeat(64);
        execute(connection, """
                INSERT INTO tenant_assets (
                    id, tenant_id, object_key, asset_type, content_type, size_bytes, sha256, status
                ) VALUES
                    ('14000000-0000-4000-8000-000000000001',
                     '10000000-0000-4000-8000-000000000001',
                     'tenant-a/images/logo-a.png', 'logo', 'image/png', 100, '%s', 'active'),
                    ('24000000-0000-4000-8000-000000000002',
                     '20000000-0000-4000-8000-000000000002',
                     'tenant-b/images/logo-b.png', 'logo', 'image/png', 100, '%s', 'active')
                """.formatted(digest, digest));
    }

    private static void assertAssetIsolationAndContentUniqueness(Connection connection) throws SQLException {
        expectSqlState(connection, "23503", """
                INSERT INTO tenant_branding (
                    tenant_id, display_name, business_type, logo_asset_id
                ) VALUES (
                    '10000000-0000-4000-8000-000000000001',
                    'Tenant A', 'restaurant',
                    '24000000-0000-4000-8000-000000000002'
                )
                """);
        expectSqlState(connection, "23505", """
                INSERT INTO tenant_assets (
                    id, tenant_id, object_key, asset_type, content_type, size_bytes, sha256, status
                ) VALUES (
                    '14100000-0000-4000-8000-000000000001',
                    '10000000-0000-4000-8000-000000000001',
                    'tenant-a/images/duplicate.png', 'logo', 'image/png', 100,
                    '%s', 'active'
                )
                """.formatted("a".repeat(64)));
        expectSqlState(connection, "23503", """
                INSERT INTO menu_items (
                    id, tenant_id, menu_version_id, category_id, public_id, name,
                    price_minor, currency, image_asset_id, sort_order
                ) VALUES (
                    '15000000-0000-4000-8000-000000000001',
                    '10000000-0000-4000-8000-000000000001',
                    '12000000-0000-4000-8000-000000000001',
                    '13000000-0000-4000-8000-000000000001',
                    'pizza-margherita', 'Margherita', 10000, 'RUB',
                    '24000000-0000-4000-8000-000000000002', 0
                )
                """);
        execute(connection, """
                INSERT INTO menu_items (
                    id, tenant_id, menu_version_id, category_id, public_id, name,
                    price_minor, currency, image_asset_id, sort_order
                ) VALUES (
                    '15000000-0000-4000-8000-000000000001',
                    '10000000-0000-4000-8000-000000000001',
                    '12000000-0000-4000-8000-000000000001',
                    '13000000-0000-4000-8000-000000000001',
                    'pizza-margherita', 'Margherita', 10000, 'RUB',
                    '14000000-0000-4000-8000-000000000001', 0
                )
                """);
    }

    private static void insertOrder(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO orders (
                    id, tenant_id, restaurant_id, menu_version_id, order_number,
                    idempotency_key, idempotency_payload_hash, status, order_type, channel,
                    customer_name, customer_phone, client_total_minor, server_total_minor,
                    currency, client_created_at, client_menu_version
                ) VALUES (
                    '16000000-0000-4000-8000-000000000001',
                    '10000000-0000-4000-8000-000000000001',
                    '11000000-0000-4000-8000-000000000001',
                    '12000000-0000-4000-8000-000000000001', 1,
                    'retry-key', '%s', 'new', 'pickup', 'web',
                    'Customer', '+79990000000', 10000, 10000, 'RUB', now(), 1
                )
                """.formatted("b".repeat(64)));
    }

    private static void assertOrderIsolationAndUniqueness(Connection connection) throws SQLException {
        expectSqlState(connection, "23505", """
                INSERT INTO orders (
                    id, tenant_id, restaurant_id, menu_version_id, order_number,
                    idempotency_key, idempotency_payload_hash, status, order_type, channel,
                    customer_name, customer_phone, client_total_minor, server_total_minor,
                    currency, client_created_at, client_menu_version
                ) VALUES (
                    '16100000-0000-4000-8000-000000000001',
                    '10000000-0000-4000-8000-000000000001',
                    '11000000-0000-4000-8000-000000000001',
                    '12000000-0000-4000-8000-000000000001', 2,
                    'retry-key', '%s', 'new', 'pickup', 'web',
                    'Customer', '+79990000000', 10000, 10000, 'RUB', now(), 1
                )
                """.formatted("c".repeat(64)));
        expectSqlState(connection, "23505", """
                INSERT INTO orders (
                    id, tenant_id, restaurant_id, menu_version_id, order_number,
                    idempotency_key, idempotency_payload_hash, status, order_type, channel,
                    customer_name, customer_phone, client_total_minor, server_total_minor,
                    currency, client_created_at, client_menu_version
                ) VALUES (
                    '16200000-0000-4000-8000-000000000001',
                    '10000000-0000-4000-8000-000000000001',
                    '11000000-0000-4000-8000-000000000001',
                    '12000000-0000-4000-8000-000000000001', 1,
                    'other-key', '%s', 'new', 'pickup', 'web',
                    'Customer', '+79990000000', 10000, 10000, 'RUB', now(), 1
                )
                """.formatted("d".repeat(64)));
        expectSqlState(connection, "23503", """
                INSERT INTO order_items (
                    id, tenant_id, order_id, menu_version_id, menu_item_id,
                    menu_item_public_id, item_name, unit_price_minor, quantity,
                    line_total_minor, sort_order
                ) VALUES (
                    '26000000-0000-4000-8000-000000000002',
                    '20000000-0000-4000-8000-000000000002',
                    '16000000-0000-4000-8000-000000000001',
                    '12000000-0000-4000-8000-000000000001',
                    '15000000-0000-4000-8000-000000000001',
                    'pizza-margherita', 'Margherita', 10000, 1, 10000, 0
                )
                """);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void expectSqlState(Connection connection, String expectedState, String sql) {
        assertThatThrownBy(() -> execute(connection, sql))
                .isInstanceOfSatisfying(SQLException.class, error ->
                        assertThat(error.getSQLState()).isEqualTo(expectedState));
    }
}
