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
    private static final String CONSTRAINT_SCHEMA = "final_constraint_test";

    @Test
    void migratesAnEmptyDatabaseSequentiallyThroughFinalVersion() throws SQLException {
        var expectedTables = Map.of(
                1, "tenants",
                2, "menu_items",
                3, "orders",
                4, "outbox_events",
                5, "legacy_import_runs",
                6, "sc_site_configuration",
                7, "site_configuration");

        for (int version = 1; version <= 7; version++) {
            var flyway = flyway(MIGRATION_SCHEMA, MigrationVersion.fromVersion(Integer.toString(version)));
            flyway.migrate();

            assertThat(flyway.validateWithResult().validationSuccessful)
                    .as("Flyway validation after V%s", version)
                    .isTrue();
            assertThat(currentVersion(MIGRATION_SCHEMA)).isEqualTo(Integer.toString(version));
            assertThat(tableExists(MIGRATION_SCHEMA, expectedTables.get(version))).isTrue();
        }

        assertThat(tableExists(MIGRATION_SCHEMA, "tenants")).isFalse();
        assertThat(tableExists(MIGRATION_SCHEMA, "sc_site_configuration")).isFalse();
        assertThat(tableExists(MIGRATION_SCHEMA, "menu_modifier_groups")).isTrue();
        assertThat(tableExists(MIGRATION_SCHEMA, "menu_modifier_options")).isTrue();
        assertThat(flyway(MIGRATION_SCHEMA, null).validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void enforcesFinalSingleClientMenuConstraints() throws SQLException {
        flyway(CONSTRAINT_SCHEMA, null).migrate();

        try (var connection = connection(CONSTRAINT_SCHEMA)) {
            execute(connection, """
                    INSERT INTO restaurants (
                        id, external_key, slug, display_name, status, is_default
                    ) VALUES (
                        '10000000-0000-4000-8000-000000000001',
                        'main', 'main', 'Main restaurant', 'active', true
                    )
                    """);
            expectSqlState(connection, "23505", """
                    INSERT INTO restaurants (
                        id, external_key, slug, display_name, status, is_default
                    ) VALUES (
                        '10000000-0000-4000-8000-000000000002',
                        'second', 'second', 'Second restaurant', 'active', true
                    )
                    """);

            execute(connection, """
                    INSERT INTO menu_versions (
                        id, restaurant_id, version, status, published_at, currency
                    ) VALUES (
                        '20000000-0000-4000-8000-000000000001',
                        '10000000-0000-4000-8000-000000000001',
                        1, 'published', now(), 'RUB'
                    )
                    """);
            expectSqlState(connection, "23505", """
                    INSERT INTO menu_versions (
                        id, restaurant_id, version, status, published_at, currency
                    ) VALUES (
                        '20000000-0000-4000-8000-000000000002',
                        '10000000-0000-4000-8000-000000000001',
                        2, 'published', now(), 'RUB'
                    )
                    """);
            expectSqlState(connection, "23514", """
                    INSERT INTO menu_versions (
                        id, restaurant_id, version, status, currency
                    ) VALUES (
                        '20000000-0000-4000-8000-000000000003',
                        '10000000-0000-4000-8000-000000000001',
                        3, 'draft', 'rub'
                    )
                    """);

            execute(connection, """
                    INSERT INTO menu_categories (
                        id, menu_version_id, public_id, name, sort_order
                    ) VALUES (
                        '30000000-0000-4000-8000-000000000001',
                        '20000000-0000-4000-8000-000000000001',
                        'pizza', 'Pizza', 0
                    )
                    """);
            execute(connection, """
                    INSERT INTO menu_items (
                        id, menu_version_id, category_id, public_id, name,
                        price_minor, currency, sort_order
                    ) VALUES (
                        '40000000-0000-4000-8000-000000000001',
                        '20000000-0000-4000-8000-000000000001',
                        '30000000-0000-4000-8000-000000000001',
                        'pepperoni', 'Pepperoni', 59000, 'RUB', 0
                    )
                    """);
            execute(connection, """
                    INSERT INTO menu_modifier_groups (
                        id, menu_version_id, menu_item_id, public_id, name,
                        min_selections, max_selections, sort_order
                    ) VALUES (
                        '50000000-0000-4000-8000-000000000001',
                        '20000000-0000-4000-8000-000000000001',
                        '40000000-0000-4000-8000-000000000001',
                        'size', 'Size', 1, 1, 0
                    )
                    """);
            execute(connection, """
                    INSERT INTO menu_modifier_options (
                        id, menu_version_id, modifier_group_id, public_id, name,
                        price_delta_minor, available, sort_order
                    ) VALUES (
                        '60000000-0000-4000-8000-000000000001',
                        '20000000-0000-4000-8000-000000000001',
                        '50000000-0000-4000-8000-000000000001',
                        'large', 'Large', 10000, true, 0
                    )
                    """);

            expectSqlState(connection, "23514", """
                    INSERT INTO menu_modifier_groups (
                        id, menu_version_id, menu_item_id, public_id, name,
                        min_selections, max_selections, sort_order
                    ) VALUES (
                        '50000000-0000-4000-8000-000000000002',
                        '20000000-0000-4000-8000-000000000001',
                        '40000000-0000-4000-8000-000000000001',
                        'invalid', 'Invalid', 2, 1, 1
                    )
                    """);
            expectSqlState(connection, "23514", """
                    INSERT INTO menu_modifier_options (
                        id, menu_version_id, modifier_group_id, public_id, name,
                        price_delta_minor, available, sort_order
                    ) VALUES (
                        '60000000-0000-4000-8000-000000000002',
                        '20000000-0000-4000-8000-000000000001',
                        '50000000-0000-4000-8000-000000000001',
                        'invalid', 'Invalid', 1000000000001, true, 1
                    )
                    """);
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
