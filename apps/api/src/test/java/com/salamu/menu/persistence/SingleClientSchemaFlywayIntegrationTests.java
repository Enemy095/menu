package com.salamu.menu.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class SingleClientSchemaFlywayIntegrationTests {

    private static final String OWNER = "10000000-0000-4000-8000-000000000001";
    private static final String RESTAURANT = "11000000-0000-4000-8000-000000000001";
    private static final String MENU_VERSION = "12000000-0000-4000-8000-000000000001";
    private static final String CATEGORY = "13000000-0000-4000-8000-000000000001";
    private static final String ASSET = "14000000-0000-4000-8000-000000000001";
    private static final String MENU_ITEM = "15000000-0000-4000-8000-000000000001";
    private static final String ORDER = "16000000-0000-4000-8000-000000000001";
    private static final String DESTINATION = "17000000-0000-4000-8000-000000000001";
    private static final String EVENT = "18000000-0000-4000-8000-000000000001";

    private static final List<String> SHADOW_TABLES = List.of(
            "sc_site_configuration",
            "sc_assets",
            "sc_site_branding",
            "sc_site_profile",
            "sc_site_features",
            "sc_restaurants",
            "sc_order_counter",
            "sc_menu_versions",
            "sc_menu_categories",
            "sc_menu_items",
            "sc_orders",
            "sc_order_items",
            "sc_order_status_history",
            "sc_notification_destinations",
            "sc_outbox_events",
            "sc_notification_deliveries");

    @Test
    void cleanEmptyDatabaseUpgradesToEmptySingleClientShadowSchema() throws SQLException {
        var schema = "sc_empty_upgrade";
        resetSchema(schema);

        var flyway = flyway(schema, null);
        assertThat(flyway.migrate().success).isTrue();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(currentVersion(schema)).isEqualTo("6");

        for (var table : SHADOW_TABLES) {
            assertThat(tableExists(schema, table)).as(table).isTrue();
            assertThat(count(schema, table)).as(table).isZero();
        }
        assertTargetMetadataHasNoTenantScope(schema);
    }

    @Test
    void emptyVersionFiveDatabaseUpgradesSeparatelyToEmptyVersionSixShadowSchema() throws SQLException {
        var schema = "sc_v5_empty_upgrade";
        resetSchema(schema);

        assertThat(flyway(schema, MigrationVersion.fromVersion("5")).migrate().success).isTrue();
        assertThat(currentVersion(schema)).isEqualTo("5");
        assertThat(flyway(schema, null).migrate().success).isTrue();

        assertThat(currentVersion(schema)).isEqualTo("6");
        for (var table : SHADOW_TABLES) {
            assertThat(count(schema, table)).as(table).isZero();
        }
    }

    @Test
    void targetAssetKeyIsExactlyDerivedAndContentTypeIsAllowlisted() throws SQLException {
        var schema = "sc_asset_constraints";
        resetSchema(schema);
        flyway(schema, null).migrate();

        try (var connection = connection(schema)) {
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO sc_assets (
                        id, legacy_object_key, canonical_object_key, storage_state,
                        asset_type, content_type, size_bytes, sha256, status
                    ) VALUES (
                        '41000000-0000-4000-8000-000000000001', 'legacy/logo.png',
                        'assets/hero/%s.png', 'legacy_only',
                        'logo', 'image/png', 10, '%s', 'active'
                    )
                    """.formatted("d".repeat(64), "d".repeat(64))))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_sc_assets_canonical_object_key");

            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO sc_assets (
                        id, legacy_object_key, canonical_object_key, storage_state,
                        asset_type, content_type, size_bytes, sha256, status
                    ) VALUES (
                        '41000000-0000-4000-8000-000000000002', 'legacy/logo.gif',
                        'assets/logo/%s.gif', 'legacy_only',
                        'logo', 'image/gif', 10, '%s', 'active'
                    )
                    """.formatted("e".repeat(64), "e".repeat(64))))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_sc_assets_content_type");
        }
    }

    @Test
    void oneOwnerGraphIsCopiedWithoutChangingBusinessValuesOrLegacyTables() throws SQLException {
        var schema = "sc_one_owner";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            insertCompleteOwnerGraph(connection);
        }

        assertThat(flyway(schema, null).migrate().success).isTrue();

        var expectedCounts = Map.ofEntries(
                Map.entry("sc_site_configuration", 1L),
                Map.entry("sc_assets", 1L),
                Map.entry("sc_site_branding", 1L),
                Map.entry("sc_site_profile", 1L),
                Map.entry("sc_site_features", 2L),
                Map.entry("sc_restaurants", 1L),
                Map.entry("sc_order_counter", 1L),
                Map.entry("sc_menu_versions", 1L),
                Map.entry("sc_menu_categories", 1L),
                Map.entry("sc_menu_items", 1L),
                Map.entry("sc_orders", 1L),
                Map.entry("sc_order_items", 1L),
                Map.entry("sc_order_status_history", 1L),
                Map.entry("sc_notification_destinations", 1L),
                Map.entry("sc_outbox_events", 1L),
                Map.entry("sc_notification_deliveries", 1L));
        for (var expected : expectedCounts.entrySet()) {
            assertThat(count(schema, expected.getKey()))
                    .as(expected.getKey())
                    .isEqualTo(expected.getValue());
        }

        assertThat(queryLong(schema, "SELECT server_total_minor FROM sc_orders WHERE id = '" + ORDER + "'"))
                .isEqualTo(15_000L);
        assertThat(queryLong(schema, "SELECT line_total_minor FROM sc_order_items WHERE order_id = '" + ORDER + "'"))
                .isEqualTo(15_000L);
        assertThat(queryString(schema, "SELECT modifier_snapshot::text FROM sc_order_items WHERE order_id = '" + ORDER + "'"))
                .isEqualTo("[{\"name\": \"extra cheese\"}]");
        assertThat(queryString(schema, "SELECT payload::text FROM sc_outbox_events WHERE id = '" + EVENT + "'"))
                .isEqualTo("{\"orderId\": \"" + ORDER + "\"}");
        assertThat(queryString(schema, "SELECT secret_reference FROM sc_notification_destinations WHERE id = '" + DESTINATION + "'"))
                .isEqualTo("secret://local/whatsapp");
        assertThat(queryString(schema, "SELECT canonical_object_key FROM sc_assets WHERE id = '" + ASSET + "'"))
                .isEqualTo("assets/logo/" + "a".repeat(64) + ".png");
        assertThat(queryString(schema, "SELECT storage_state FROM sc_assets WHERE id = '" + ASSET + "'"))
                .isEqualTo("legacy_only");

        assertThat(count(schema, "tenants")).isOne();
        assertThat(count(schema, "orders")).isOne();
        assertThat(count(schema, "tenant_assets")).isOne();
        assertThat(tableExists(schema, "legacy_import_runs")).isTrue();
        assertThat(publicFunctionExecuteGrants(schema, "sc_assert_single_client_source")).isZero();
        assertThat(publicFunctionExecuteGrants(schema, "sc_refresh_single_client_shadow")).isZero();
        assertTargetMetadataHasNoTenantScope(schema);
    }

    @Test
    void refreshAfterLateLegacyWriteRebuildsShadowUnderReusableContract() throws SQLException {
        var schema = "sc_late_refresh";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            insertCompleteOwnerGraph(connection);
        }
        flyway(schema, null).migrate();

        try (var connection = connection(schema)) {
            execute(connection, "UPDATE restaurants SET display_name = 'Changed after V6' WHERE id = '" + RESTAURANT + "'");
            execute(connection, """
                    INSERT INTO tenant_features (tenant_id, feature_key, enabled, config)
                    VALUES ('%s', 'delivery', true, '{"lateWrite":true}')
                    """.formatted(OWNER));
            execute(connection, "UPDATE sc_restaurants SET display_name = 'stale shadow' WHERE id = '" + RESTAURANT + "'");
            execute(connection, "UPDATE sc_assets SET storage_state = 'canonical_ready' WHERE id = '" + ASSET + "'");
            execute(connection, "SELECT sc_refresh_single_client_shadow()");
        }

        assertThat(queryString(schema, "SELECT display_name FROM sc_restaurants WHERE id = '" + RESTAURANT + "'"))
                .isEqualTo("Changed after V6");
        assertThat(count(schema, "sc_site_features")).isEqualTo(3);
        assertThat(queryString(schema, "SELECT config::text FROM sc_site_features WHERE feature_key = 'delivery'"))
                .isEqualTo("{\"lateWrite\": true}");
        assertThat(queryString(schema, "SELECT storage_state FROM sc_assets WHERE id = '" + ASSET + "'"))
                .isEqualTo("canonical_ready");
    }

    @Test
    void sourceAssertionHoldsWriteBarrierUntilCallingTransactionEnds() throws SQLException {
        var schema = "sc_source_write_barrier";
        resetSchema(schema);
        flyway(schema, null).migrate();

        try (var lockOwner = connection(schema); var legacyWriter = connection(schema)) {
            lockOwner.setAutoCommit(false);
            execute(lockOwner, "SELECT sc_assert_single_client_source()");
            execute(legacyWriter, "SET lock_timeout = '250ms'");

            assertThatThrownBy(() -> execute(legacyWriter, """
                    INSERT INTO tenants (id, slug, status, locale, currency, timezone)
                    VALUES (
                        '42000000-0000-4000-8000-000000000001', 'blocked-writer',
                        'active', 'ru-RU', 'RUB', 'Europe/Moscow'
                    )
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("lock timeout");

            lockOwner.rollback();
        }
    }

    @Test
    void refreshFailsWhenCopiedShadowValueDiffersFromSource() throws SQLException {
        var schema = "sc_except_all_guard";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            insertCompleteOwnerGraph(connection);
        }
        flyway(schema, null).migrate();

        try (var connection = connection(schema)) {
            execute(connection, """
                    CREATE FUNCTION corrupt_sc_restaurant_refresh()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $function$
                    BEGIN
                        NEW.display_name := 'corrupted by test trigger';
                        RETURN NEW;
                    END
                    $function$
                    """);
            execute(connection, """
                    CREATE TRIGGER corrupt_sc_restaurant_refresh
                    BEFORE INSERT ON sc_restaurants
                    FOR EACH ROW EXECUTE FUNCTION corrupt_sc_restaurant_refresh()
                    """);

            assertThatThrownBy(() -> execute(connection, "SELECT sc_refresh_single_client_shadow()"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("SC_DB_001_SHADOW_DATA_MISMATCH");
        }

        assertThat(queryString(schema, "SELECT display_name FROM sc_restaurants WHERE id = '" + RESTAURANT + "'"))
                .isEqualTo("Main restaurant");
        assertThat(count(schema, "restaurants")).isOne();
    }

    @Test
    void twoOwnersAbortBeforeShadowDdlAndPreserveLegacyData() throws SQLException {
        var schema = "sc_two_owners";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            execute(connection, """
                    INSERT INTO tenants (id, slug, status, locale, currency, timezone)
                    VALUES
                        ('10000000-0000-4000-8000-000000000001', 'first', 'active', 'ru-RU', 'RUB', 'Europe/Moscow'),
                        ('20000000-0000-4000-8000-000000000002', 'second', 'active', 'ru-RU', 'RUB', 'Europe/Moscow')
                    """);
            execute(connection, """
                    INSERT INTO restaurants (id, tenant_id, slug, display_name, status, is_default)
                    VALUES
                        ('11000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001',
                         'first', 'First', 'active', true),
                        ('22000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000002',
                         'second', 'Second', 'active', true)
                    """);
        }

        assertMigrationFailsWith(schema, "SC_DB_001_MULTI_OWNER");

        assertThat(count(schema, "tenants")).isEqualTo(2);
        assertThat(count(schema, "restaurants")).isEqualTo(2);
        assertThat(tableExists(schema, "sc_site_configuration")).isFalse();
        assertThat(currentVersion(schema)).isEqualTo("5");
    }

    @Test
    void futureGlobalCollisionAbortsAndPreservesSourceRows() throws SQLException {
        var schema = "sc_collision";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            insertCompleteOwnerGraph(connection);
            execute(connection, "ALTER TABLE orders DROP CONSTRAINT uq_orders_idempotency");
            execute(connection, """
                    INSERT INTO orders (
                        id, tenant_id, restaurant_id, menu_version_id, order_number,
                        idempotency_key, idempotency_payload_hash, status, order_type, channel,
                        customer_name, customer_phone, client_total_minor, server_total_minor,
                        currency, client_created_at, client_menu_version
                    ) VALUES (
                        '26000000-0000-4000-8000-000000000002',
                        '10000000-0000-4000-8000-000000000001',
                        '11000000-0000-4000-8000-000000000001',
                        '12000000-0000-4000-8000-000000000001', 2,
                        'retry-key', '%s', 'new', 'pickup', 'web',
                        'Second customer', '+79990000001', 15000, 15000, 'RUB', now(), 1
                    )
                    """.formatted("c".repeat(64)));
        }

        assertMigrationFailsWith(schema, "SC_DB_001_FUTURE_GLOBAL_KEY_COLLISION");
        assertThat(count(schema, "orders")).isEqualTo(2);
        assertThat(tableExists(schema, "sc_orders")).isFalse();
    }

    @Test
    void orphanReferenceAbortsAndPreservesSourceRows() throws SQLException {
        var schema = "sc_orphan";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            insertCompleteOwnerGraph(connection);
            execute(connection, "ALTER TABLE menu_items DROP CONSTRAINT fk_menu_items_category");
            execute(connection, """
                    UPDATE menu_items
                    SET category_id = '33000000-0000-4000-8000-000000000003'
                    WHERE id = '15000000-0000-4000-8000-000000000001'
                    """);
        }

        assertMigrationFailsWith(schema, "SC_DB_001_CROSS_OWNER_OR_ORPHAN_REFERENCE");
        assertThat(count(schema, "menu_items")).isOne();
        assertThat(tableExists(schema, "sc_menu_items")).isFalse();
    }

    @Test
    void populatedRestaurantsRequireExactlyOneActiveDefault() throws SQLException {
        var schema = "sc_default_required";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            insertCompleteOwnerGraph(connection);
            execute(connection, "UPDATE restaurants SET is_default = false WHERE id = '" + RESTAURANT + "'");
        }

        assertMigrationFailsWith(schema, "SC_DB_001_ACTIVE_DEFAULT_RESTAURANT_REQUIRED");
        assertThat(tableExists(schema, "sc_restaurants")).isFalse();
    }

    @Test
    void orderCounterMustExistAndBeAheadOfMaximumOrderNumber() throws SQLException {
        var schema = "sc_counter_behind";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            insertCompleteOwnerGraph(connection);
            execute(connection, "UPDATE tenant_order_counters SET next_value = 1 WHERE tenant_id = '" + OWNER + "'");
        }

        assertMigrationFailsWith(schema, "SC_DB_001_ORDER_COUNTER_INVALID");
        assertThat(tableExists(schema, "sc_order_counter")).isFalse();
    }

    @Test
    void nonEmptyOrdersRequireOrderCounterRow() throws SQLException {
        var schema = "sc_counter_missing";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            insertCompleteOwnerGraph(connection);
            execute(connection, "DELETE FROM tenant_order_counters WHERE tenant_id = '" + OWNER + "'");
        }

        assertMigrationFailsWith(schema, "SC_DB_001_ORDER_COUNTER_INVALID");
        assertThat(tableExists(schema, "sc_order_counter")).isFalse();
    }

    @Test
    void unsupportedOutboxAggregateFailsClosed() throws SQLException {
        var schema = "sc_outbox_unsupported";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            insertCompleteOwnerGraph(connection);
            execute(connection, "UPDATE outbox_events SET aggregate_type = 'invoice' WHERE id = '" + EVENT + "'");
        }

        assertMigrationFailsWith(schema, "SC_DB_001_UNSUPPORTED_OUTBOX_AGGREGATE");
        assertThat(tableExists(schema, "sc_outbox_events")).isFalse();
    }

    @Test
    void orderOutboxAggregateMustReferenceSameOwnerOrder() throws SQLException {
        var schema = "sc_outbox_orphan";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            insertCompleteOwnerGraph(connection);
            execute(connection, """
                    UPDATE outbox_events
                    SET aggregate_id = '99999999-0000-4000-8000-000000000999'
                    WHERE id = '%s'
                    """.formatted(EVENT));
        }

        assertMigrationFailsWith(schema, "SC_DB_001_ORPHAN_OUTBOX_ORDER");
        assertThat(tableExists(schema, "sc_outbox_events")).isFalse();
    }

    @Test
    void unsupportedAssetContentTypeFailsBeforeShadowDdl() throws SQLException {
        var schema = "sc_asset_content_type";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            insertCompleteOwnerGraph(connection);
            execute(connection, "UPDATE tenant_assets SET content_type = 'image/gif' WHERE id = '" + ASSET + "'");
        }

        assertMigrationFailsWith(schema, "SC_DB_001_UNSUPPORTED_ASSET_CONTENT_TYPE");
        assertThat(tableExists(schema, "sc_assets")).isFalse();
    }

    private static void insertCompleteOwnerGraph(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO tenants (id, slug, status, locale, currency, timezone)
                VALUES ('%s', 'single-site', 'active', 'ru-RU', 'RUB', 'Europe/Moscow')
                """.formatted(OWNER));
        execute(connection, """
                INSERT INTO tenant_assets (
                    id, tenant_id, object_key, asset_type, content_type, size_bytes, sha256, status
                ) VALUES (
                    '%s', '%s', 'tenants/%s/logo/%s.png', 'logo', 'image/png', 100, '%s', 'active'
                )
                """.formatted(ASSET, OWNER, OWNER, "a".repeat(64), "a".repeat(64)));
        execute(connection, """
                INSERT INTO tenant_branding (
                    tenant_id, display_name, business_type, logo_asset_id,
                    design_tokens, localized_texts, hero, seo_metadata
                ) VALUES (
                    '%s', 'Single site', 'restaurant', '%s',
                    '{"primaryColor":"#000000"}', '[]', '{}', '{}'
                )
                """.formatted(OWNER, ASSET));
        execute(connection, """
                INSERT INTO tenant_profiles (
                    tenant_id, public_phone, public_phone_label, working_hours_text
                ) VALUES ('%s', '+79990000000', 'Call', 'Always')
                """.formatted(OWNER));
        execute(connection, """
                INSERT INTO tenant_features (tenant_id, feature_key, enabled, config)
                VALUES
                    ('%s', 'orders', true, '{"limits":{}}'),
                    ('%s', 'pickup', true, '{"label":"Pickup","default":true}')
                """.formatted(OWNER, OWNER));
        execute(connection, """
                INSERT INTO restaurants (id, tenant_id, slug, display_name, status, is_default)
                VALUES ('%s', '%s', 'main', 'Main restaurant', 'active', true)
                """.formatted(RESTAURANT, OWNER));
        execute(connection, """
                INSERT INTO tenant_order_counters (tenant_id, next_value)
                VALUES ('%s', 2)
                """.formatted(OWNER));
        execute(connection, """
                INSERT INTO menu_versions (id, tenant_id, restaurant_id, version, status, published_at)
                VALUES ('%s', '%s', '%s', 1, 'published', now())
                """.formatted(MENU_VERSION, OWNER, RESTAURANT));
        execute(connection, """
                INSERT INTO menu_categories (
                    id, tenant_id, menu_version_id, public_id, name, sort_order
                ) VALUES ('%s', '%s', '%s', 'pizza', 'Pizza', 0)
                """.formatted(CATEGORY, OWNER, MENU_VERSION));
        execute(connection, """
                INSERT INTO menu_items (
                    id, tenant_id, menu_version_id, category_id, public_id, name,
                    price_minor, currency, image_asset_id, sort_order
                ) VALUES (
                    '%s', '%s', '%s', '%s', 'pizza-cheese', 'Cheese pizza',
                    15000, 'RUB', '%s', 0
                )
                """.formatted(MENU_ITEM, OWNER, MENU_VERSION, CATEGORY, ASSET));
        execute(connection, """
                INSERT INTO orders (
                    id, tenant_id, restaurant_id, menu_version_id, order_number,
                    idempotency_key, idempotency_payload_hash, status, order_type, channel,
                    customer_name, customer_phone, client_total_minor, server_total_minor,
                    currency, client_created_at, client_menu_version
                ) VALUES (
                    '%s', '%s', '%s', '%s', 1,
                    'retry-key', '%s', 'new', 'pickup', 'web',
                    'Customer', '+79990000000', 15000, 15000, 'RUB', now(), 1
                )
                """.formatted(ORDER, OWNER, RESTAURANT, MENU_VERSION, "b".repeat(64)));
        execute(connection, """
                INSERT INTO order_items (
                    id, tenant_id, order_id, menu_version_id, menu_item_id,
                    menu_item_public_id, item_name, unit_price_minor, modifier_snapshot,
                    quantity, line_total_minor, sort_order
                ) VALUES (
                    '16100000-0000-4000-8000-000000000001', '%s', '%s', '%s', '%s',
                    'pizza-cheese', 'Cheese pizza', 15000,
                    '[{"name":"extra cheese"}]', 1, 15000, 0
                )
                """.formatted(OWNER, ORDER, MENU_VERSION, MENU_ITEM));
        execute(connection, """
                INSERT INTO order_status_history (
                    id, tenant_id, order_id, from_status, to_status, actor_type, metadata
                ) VALUES (
                    '16200000-0000-4000-8000-000000000001', '%s', '%s', null, 'new', 'system', '{}'
                )
                """.formatted(OWNER, ORDER));
        execute(connection, """
                INSERT INTO notification_destinations (
                    id, tenant_id, channel, status, display_name, secret_reference
                ) VALUES (
                    '%s', '%s', 'whatsapp', 'active', 'orders', 'secret://local/whatsapp'
                )
                """.formatted(DESTINATION, OWNER));
        execute(connection, """
                INSERT INTO outbox_events (
                    id, tenant_id, aggregate_type, aggregate_id, event_type,
                    schema_version, payload, processing_state, processed_at
                ) VALUES (
                    '%s', '%s', 'order', '%s', 'order.created', 1,
                    '{"orderId":"%s"}', 'published', now()
                )
                """.formatted(EVENT, OWNER, ORDER, ORDER));
        execute(connection, """
                INSERT INTO notification_deliveries (
                    id, tenant_id, outbox_event_id, destination_id, attempt_number,
                    status, completed_at
                ) VALUES (
                    '18100000-0000-4000-8000-000000000001', '%s', '%s', '%s', 1,
                    'succeeded', now()
                )
                """.formatted(OWNER, EVENT, DESTINATION));
    }

    private static void assertTargetMetadataHasNoTenantScope(String schema) throws SQLException {
        assertThat(queryLong(schema, """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name LIKE 'sc\\_%' ESCAPE '\\'
                  AND column_name = 'tenant_id'
                """)).isZero();
        assertThat(queryLong(schema, """
                SELECT count(*)
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename LIKE 'sc\\_%' ESCAPE '\\'
                  AND lower(indexdef) LIKE '%%tenant_id%%'
                """)).isZero();
        assertThat(queryLong(schema, """
                SELECT count(*)
                FROM pg_constraint constraint_record
                JOIN pg_class target ON target.oid = constraint_record.conrelid
                JOIN pg_namespace namespace ON namespace.oid = target.relnamespace
                WHERE namespace.nspname = current_schema()
                  AND target.relname LIKE 'sc\\_%' ESCAPE '\\'
                  AND lower(pg_get_constraintdef(constraint_record.oid)) LIKE '%%tenant_id%%'
                """)).isZero();
    }

    private static void assertMigrationFailsWith(String schema, String stableDiagnostic) {
        assertThatThrownBy(() -> flyway(schema, null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining(stableDiagnostic);
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

    private static void resetSchema(String schema) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        PostgreSqlTestContainer.INSTANCE.getJdbcUrl(),
                        PostgreSqlTestContainer.INSTANCE.getUsername(),
                        PostgreSqlTestContainer.INSTANCE.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
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
        return queryString(schema, """
                SELECT version
                FROM flyway_schema_history
                WHERE success
                ORDER BY installed_rank DESC
                LIMIT 1
                """);
    }

    private static long count(String schema, String table) throws SQLException {
        return queryLong(schema, "SELECT count(*) FROM " + table);
    }

    private static long publicFunctionExecuteGrants(String schema, String functionName) throws SQLException {
        return queryLong(schema, """
                SELECT count(*)
                FROM pg_proc function_record
                JOIN pg_namespace namespace ON namespace.oid = function_record.pronamespace
                CROSS JOIN LATERAL aclexplode(
                    COALESCE(function_record.proacl, acldefault('f', function_record.proowner))
                ) privilege
                WHERE namespace.nspname = current_schema()
                  AND function_record.proname = '%s'
                  AND privilege.grantee = 0
                  AND privilege.privilege_type = 'EXECUTE'
                """.formatted(functionName));
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

    private static long queryLong(String schema, String sql) throws SQLException {
        try (var connection = connection(schema);
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static String queryString(String schema, String sql) throws SQLException {
        try (var connection = connection(schema);
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
