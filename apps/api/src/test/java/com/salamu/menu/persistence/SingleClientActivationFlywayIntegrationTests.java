package com.salamu.menu.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class SingleClientActivationFlywayIntegrationTests {

    private static final String OWNER = "10000000-0000-4000-8000-000000000001";
    private static final String RESTAURANT = "11000000-0000-4000-8000-000000000001";
    private static final String MENU_VERSION = "12000000-0000-4000-8000-000000000001";
    private static final String EMPTY_DRAFT_MENU_VERSION = "12100000-0000-4000-8000-000000000001";
    private static final String CATEGORY = "13000000-0000-4000-8000-000000000001";
    private static final String ASSET = "14000000-0000-4000-8000-000000000001";
    private static final String MENU_ITEM = "15000000-0000-4000-8000-000000000001";
    private static final String ORDER = "16000000-0000-4000-8000-000000000001";
    private static final String DESTINATION = "17000000-0000-4000-8000-000000000001";
    private static final String EVENT = "18000000-0000-4000-8000-000000000001";
    private static final String RUNTIME_ROLE = "sc_v7_application_runtime";
    private static final String SOURCE_FINGERPRINT_SQL = """
            WITH shadow_rows(table_name, row_key, row_data) AS (
                SELECT 'sc_site_configuration', singleton_id::text, to_jsonb(source_row)::text
                FROM sc_site_configuration source_row
                UNION ALL SELECT 'sc_assets', id::text, to_jsonb(source_row)::text FROM sc_assets source_row
                UNION ALL SELECT 'sc_site_branding', singleton_id::text, to_jsonb(source_row)::text
                    FROM sc_site_branding source_row
                UNION ALL SELECT 'sc_site_profile', singleton_id::text, to_jsonb(source_row)::text
                    FROM sc_site_profile source_row
                UNION ALL SELECT 'sc_site_features', feature_key, to_jsonb(source_row)::text
                    FROM sc_site_features source_row
                UNION ALL SELECT 'sc_restaurants', id::text, to_jsonb(source_row)::text FROM sc_restaurants source_row
                UNION ALL SELECT 'sc_order_counter', singleton_id::text, to_jsonb(source_row)::text
                    FROM sc_order_counter source_row
                UNION ALL SELECT 'sc_menu_versions', id::text, to_jsonb(source_row)::text FROM sc_menu_versions source_row
                UNION ALL SELECT 'sc_menu_categories', id::text, to_jsonb(source_row)::text FROM sc_menu_categories source_row
                UNION ALL SELECT 'sc_menu_items', id::text, to_jsonb(source_row)::text FROM sc_menu_items source_row
                UNION ALL SELECT 'sc_orders', id::text, to_jsonb(source_row)::text FROM sc_orders source_row
                UNION ALL SELECT 'sc_order_items', id::text, to_jsonb(source_row)::text FROM sc_order_items source_row
                UNION ALL SELECT 'sc_order_status_history', id::text, to_jsonb(source_row)::text
                    FROM sc_order_status_history source_row
                UNION ALL SELECT 'sc_notification_destinations', id::text, to_jsonb(source_row)::text
                    FROM sc_notification_destinations source_row
                UNION ALL SELECT 'sc_outbox_events', id::text, to_jsonb(source_row)::text FROM sc_outbox_events source_row
                UNION ALL SELECT 'sc_notification_deliveries', id::text, to_jsonb(source_row)::text
                    FROM sc_notification_deliveries source_row
            )
            SELECT md5(COALESCE(string_agg(
                table_name || chr(31) || row_key || chr(31) || row_data,
                chr(30) ORDER BY table_name, row_key
            ), ''))
            FROM shadow_rows
            """;

    @Test
    void cleanEmptyDatabaseActivatesOnlyFinalSingleClientSchema() throws SQLException {
        var schema = "sc_v7_empty";
        resetSchema(schema);

        var flyway = flyway(schema, null);
        assertThat(flyway.migrate().success).isTrue();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        assertThat(currentVersion(schema)).isEqualTo("7");
        assertThat(tableExists(schema, "site_configuration")).isTrue();
        assertThat(tableExists(schema, "menu_modifier_groups")).isTrue();
        assertThat(tableExists(schema, "menu_modifier_options")).isTrue();
        assertThat(count(schema, "site_configuration")).isZero();
        assertThat(count(schema, "menu_modifier_groups")).isZero();
        assertThat(tableExists(schema, "tenants")).isFalse();
        assertThat(tableExists(schema, "sc_site_configuration")).isFalse();
        assertThat(functionExists(schema, "sc_refresh_single_client_shadow")).isFalse();
        assertThat(functionExists(schema, "sc_assert_single_client_source")).isFalse();
        assertCleanFinalCatalog(schema);
    }

    @Test
    void populatedCanonicalReadyGraphActivatesAndKeepsBusinessHistory() throws SQLException {
        var schema = "sc_v7_populated";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            insertCompleteOwnerGraph(connection);
        }
        flyway(schema, MigrationVersion.fromVersion("6")).migrate();
        try (var connection = connection(schema)) {
            execute(connection, "UPDATE sc_assets SET storage_state = 'canonical_ready' WHERE id = '" + ASSET + "'");
        }
        ensureRuntimeRole(schema);
        var approvedFingerprint = queryString(schema, SOURCE_FINGERPRINT_SQL);

        assertThat(approvedFlyway(schema, approvedFingerprint, RUNTIME_ROLE).migrate().success).isTrue();

        assertThat(currentVersion(schema)).isEqualTo("7");
        assertThat(count(schema, "restaurants")).isOne();
        assertThat(count(schema, "menu_versions")).isEqualTo(2);
        assertThat(count(schema, "orders")).isOne();
        assertThat(count(schema, "order_items")).isOne();
        assertThat(count(schema, "outbox_events")).isOne();
        assertThat(queryLong(schema, "SELECT server_total_minor FROM orders WHERE id = '" + ORDER + "'"))
                .isEqualTo(15_000L);
        assertThat(queryString(schema, "SELECT modifier_snapshot::text FROM order_items WHERE order_id = '" + ORDER + "'"))
                .isEqualTo("[{\"name\": \"extra cheese\"}]");
        assertThat(queryString(schema, "SELECT object_key FROM assets WHERE id = '" + ASSET + "'"))
                .isEqualTo("assets/logo/" + "a".repeat(64) + ".png");
        assertThat(queryString(schema, "SELECT currency FROM menu_versions WHERE id = '" + MENU_VERSION + "'"))
                .isEqualTo("RUB");
        assertThat(queryString(schema, "SELECT currency FROM menu_versions WHERE id = '"
                + EMPTY_DRAFT_MENU_VERSION + "'"))
                .isEqualTo("RUB");
        assertThat(columnExists(schema, "assets", "legacy_object_key")).isFalse();
        assertThat(columnExists(schema, "assets", "storage_state")).isFalse();
        assertThat(count(schema, "menu_modifier_groups")).isZero();
        assertThat(count(schema, "menu_modifier_options")).isZero();

        try (var connection = connection(schema)) {
            execute(connection, """
                    INSERT INTO menu_modifier_groups (
                        id, menu_version_id, menu_item_id, public_id, name,
                        min_selections, max_selections, sort_order
                    ) VALUES (
                        '19000000-0000-4000-8000-000000000001', '%s', '%s',
                        'cheese', 'Cheese', 0, 2, 0
                    )
                    """.formatted(MENU_VERSION, MENU_ITEM));
            execute(connection, """
                    INSERT INTO menu_modifier_options (
                        id, menu_version_id, modifier_group_id, public_id, name,
                        price_delta_minor, available, sort_order
                    ) VALUES (
                        '19100000-0000-4000-8000-000000000001', '%s',
                        '19000000-0000-4000-8000-000000000001',
                        'extra-cheese', 'Extra cheese', 2500, true, 0
                    )
                    """.formatted(MENU_VERSION));
        }
        assertThat(count(schema, "menu_modifier_groups")).isOne();
        assertThat(count(schema, "menu_modifier_options")).isOne();
        assertThat(indexExists(schema, "ix_menu_modifier_groups_item_ordered")).isTrue();
        assertThat(indexExists(schema, "ix_menu_modifier_options_group_ordered")).isTrue();
        assertCleanFinalCatalog(schema);
    }

    @Test
    void sizeChangeCannotInheritReadinessAndRollsBackWholeContract() throws SQLException {
        var schema = "sc_v7_size_rollback";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            insertCompleteOwnerGraph(connection);
        }
        flyway(schema, MigrationVersion.fromVersion("6")).migrate();
        try (var connection = connection(schema)) {
            execute(connection, "UPDATE sc_assets SET storage_state = 'canonical_ready' WHERE id = '" + ASSET + "'");
            execute(connection, "UPDATE tenant_assets SET size_bytes = size_bytes + 1 WHERE id = '" + ASSET + "'");
        }

        assertThatThrownBy(() -> flyway(schema, null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("SC_BE_001_ASSET_READINESS_IDENTITY_CHANGED");

        assertThat(currentVersion(schema)).isEqualTo("6");
        assertThat(tableExists(schema, "tenants")).isTrue();
        assertThat(tableExists(schema, "sc_site_configuration")).isTrue();
        assertThat(tableExists(schema, "site_configuration")).isFalse();
        assertThat(count(schema, "orders")).isOne();
        assertThat(count(schema, "sc_orders")).isOne();
        assertThat(functionExists(schema, "sc_refresh_single_client_shadow")).isTrue();
    }

    @Test
    void menuCurrencyMismatchFailsBeforeContractAndPreservesBothSchemas() throws SQLException {
        var schema = "sc_v7_currency_rollback";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            insertCompleteOwnerGraph(connection);
            execute(connection, "UPDATE menu_items SET currency = 'USD' WHERE id = '" + MENU_ITEM + "'");
        }
        flyway(schema, MigrationVersion.fromVersion("6")).migrate();
        try (var connection = connection(schema)) {
            execute(connection, "UPDATE sc_assets SET storage_state = 'canonical_ready' WHERE id = '" + ASSET + "'");
        }

        assertThatThrownBy(() -> flyway(schema, null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("SC_BE_001_MENU_CURRENCY_INVALID");

        assertThat(currentVersion(schema)).isEqualTo("6");
        assertThat(tableExists(schema, "menu_versions")).isTrue();
        assertThat(tableExists(schema, "sc_menu_versions")).isTrue();
        assertThat(columnExists(schema, "menu_versions", "currency")).isFalse();
        assertThat(queryString(schema, "SELECT currency FROM menu_items WHERE id = '" + MENU_ITEM + "'"))
                .isEqualTo("USD");
    }

    @Test
    void malformedOpaqueDestinationReferenceFailsBeforeContract() throws SQLException {
        var schema = "sc_v7_destination_rollback";
        preparePopulatedShadow(schema, """
                UPDATE notification_destinations
                SET secret_reference = 'secret://manager'
                WHERE id = '%s'
                """.formatted(DESTINATION));

        assertCutoverRejected(schema, "SC_BE_001_NOTIFICATION_DESTINATION_INVALID");
    }

    @Test
    void pendingOutboxBacklogIsPreservedUnchangedAcrossApprovedCutover() throws SQLException {
        var schema = "sc_v7_pending_preserved";
        preparePopulatedShadow(schema, """
                DELETE FROM notification_deliveries
                WHERE outbox_event_id = '%s';
                UPDATE outbox_events
                SET processing_state = 'pending', processed_at = null
                WHERE id = '%s'
                """.formatted(EVENT, EVENT));
        ensureRuntimeRole(schema);
        var approvedFingerprint = queryString(schema, SOURCE_FINGERPRINT_SQL);

        assertThat(approvedFlyway(schema, approvedFingerprint, RUNTIME_ROLE).migrate().success).isTrue();
        assertThat(queryString(schema, "SELECT processing_state FROM outbox_events WHERE id = '" + EVENT + "'"))
                .isEqualTo("pending");
        assertThat(queryString(schema, "SELECT payload::text FROM outbox_events WHERE id = '" + EVENT + "'"))
                .isEqualTo("{\"orderId\": \"" + ORDER + "\"}");
        assertThat(queryLong(schema, "SELECT count(*) FROM notification_deliveries")).isZero();
    }

    @Test
    void processingOutboxWorkStillBlocksCutover() throws SQLException {
        var schema = "sc_v7_processing_rollback";
        preparePopulatedShadow(schema, """
                UPDATE outbox_events
                SET processing_state = 'processing', processed_at = null,
                    leased_until = now() + interval '5 minutes', lease_owner = 'worker-1'
                WHERE id = '%s'
                """.formatted(EVENT));

        assertCutoverRejected(schema, "SC_BE_001_OUTBOX_RECONCILIATION_REQUIRED");
    }

    @Test
    void startedDeliveryStillBlocksCutover() throws SQLException {
        var schema = "sc_v7_started_delivery_rollback";
        preparePopulatedShadow(schema, """
                UPDATE notification_deliveries
                SET status = 'started', completed_at = null
                WHERE outbox_event_id = '%s'
                """.formatted(EVENT));

        assertCutoverRejected(schema, "SC_BE_001_OUTBOX_RECONCILIATION_REQUIRED");
    }

    @Test
    void invalidTypedBusinessProjectionFailsBeforeApprovalAndContract() throws SQLException {
        var schema = "sc_v7_projection_rollback";
        preparePopulatedShadow(schema, """
                UPDATE tenant_features
                SET config = jsonb_set(config, '{limits,maxItemQuantity}', '100'::jsonb)
                WHERE tenant_id = '%s' AND feature_key = 'orders'
                """.formatted(OWNER));

        assertCutoverRejected(schema, "SC_BE_001_BUSINESS_PROJECTION_INVALID");
    }

    @Test
    void populatedCutoverRequiresExplicitApprovalFingerprint() throws SQLException {
        var schema = "sc_v7_approval_missing";
        preparePopulatedShadow(schema, null);
        ensureRuntimeRole(schema);

        assertCutoverRejected(schema, "SC_BE_001_APPROVED_PROJECTION_MISSING");
    }

    @Test
    void changedApprovalFingerprintRollsBackWholeContract() throws SQLException {
        var schema = "sc_v7_approval_mismatch";
        preparePopulatedShadow(schema, null);
        ensureRuntimeRole(schema);

        assertThatThrownBy(() -> approvedFlyway(schema, "0".repeat(32), RUNTIME_ROLE).migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("SC_BE_001_APPROVED_SOURCE_FINGERPRINT_MISMATCH");
        assertContractRolledBack(schema);
    }

    @Test
    void runtimeRoleCannotBeTheMigrationOwner() throws SQLException {
        var schema = "sc_v7_role_rollback";
        preparePopulatedShadow(schema, null);
        var approvedFingerprint = queryString(schema, SOURCE_FINGERPRINT_SQL);
        var migrationRole = queryString(schema, "SELECT current_user");

        assertThatThrownBy(() -> approvedFlyway(schema, approvedFingerprint, migrationRole).migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("SC_BE_001_DATABASE_ROLE_SEPARATION_REQUIRED");
        assertContractRolledBack(schema);
    }

    @Test
    void configuredRuntimeRoleReceivesOnlyFinalApplicationCrudPrivileges() throws SQLException {
        var schema = "sc_v7_empty_runtime_grants";
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("6")).migrate();
        ensureRuntimeRole(schema);
        var migrator = queryString(schema, "SELECT current_user");

        assertThat(runtimeRoleFlyway(schema, RUNTIME_ROLE).migrate().success).isTrue();
        assertThat(queryString(schema, """
                SELECT pg_get_userbyid(table_record.relowner)
                FROM pg_class table_record
                JOIN pg_namespace namespace ON namespace.oid = table_record.relnamespace
                WHERE namespace.nspname = current_schema()
                  AND table_record.relname = 'site_configuration'
                """)).isEqualTo(migrator);

        try (var runtime = connection(schema)) {
            runtime.setAutoCommit(false);
            execute(runtime, "SET ROLE " + RUNTIME_ROLE);
            assertThat(queryLong(runtime, "SELECT count(*) FROM site_configuration")).isZero();
            execute(runtime, """
                    INSERT INTO site_configuration (
                        singleton_id, status, locale, currency, timezone
                    ) VALUES (1, 'active', 'en-GB', 'GBP', 'Europe/London')
                    """);
            execute(runtime, "UPDATE site_configuration SET timezone = 'UTC' WHERE singleton_id = 1");
            assertThat(queryString(runtime, "SELECT timezone FROM site_configuration WHERE singleton_id = 1"))
                    .isEqualTo("UTC");
            execute(runtime, "DELETE FROM site_configuration WHERE singleton_id = 1");
            runtime.rollback();
        }
        assertThat(count(schema, "site_configuration")).isZero();

        assertRuntimeSqlRejected(schema, "CREATE TABLE runtime_forbidden (id integer)");
        assertRuntimeSqlRejected(schema, "ALTER TABLE site_configuration ADD COLUMN runtime_forbidden integer");
        assertRuntimeSqlRejected(schema, "TRUNCATE TABLE site_configuration");
        assertRuntimeSqlRejected(schema, "SELECT sc_refresh_single_client_shadow()");
        assertThat(tableExists(schema, "runtime_forbidden")).isFalse();
        assertThat(columnExists(schema, "site_configuration", "runtime_forbidden")).isFalse();
        assertThat(functionExists(schema, "sc_refresh_single_client_shadow")).isFalse();
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
                    '{
                      "primaryColor":"#111111","secondaryColor":"#222222","accentColor":"#333333",
                      "backgroundColor":"#ffffff","surfaceColor":"#f5f5f5","textColor":"#101010",
                      "mutedTextColor":"#666666","borderRadius":"8px","fontFamily":"Arial"
                    }',
                    '[{
                      "locale":"ru-RU","displayName":"Single site","shortName":"Site",
                      "businessTypeLabel":"Restaurant","cuisineText":"","slogan":"","introText":"",
                      "menuSectionTitle":"Menu","cartTitle":"Cart","orderTypeLegend":"Order type"
                    }]',
                    '{"title":"Welcome","ctaLabel":"View menu","benefits":[]}',
                    '{
                      "title":"Single site","description":"Restaurant menu",
                      "canonicalUrl":"https://example.test/"
                    }'
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
                    ('%s', 'orders', true, '{"limits":{
                      "maxItemQuantity":99,"maxTotalItems":200,"maxNameLength":80,
                      "maxPhoneLength":32,"maxAddressLength":240,"maxCommentLength":500,
                      "delayedOrderConfirmationMinutes":0
                    }}'),
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
                VALUES
                    ('%s', '%s', '%s', 1, 'published', now()),
                    ('%s', '%s', '%s', 2, 'draft', null)
                """.formatted(
                MENU_VERSION, OWNER, RESTAURANT,
                EMPTY_DRAFT_MENU_VERSION, OWNER, RESTAURANT));
        execute(connection, """
                INSERT INTO menu_categories (
                    id, tenant_id, menu_version_id, public_id, name, sort_order
                ) VALUES ('%s', '%s', '%s', 'pizza', 'Pizza', 0)
                """.formatted(CATEGORY, OWNER, MENU_VERSION));
        execute(connection, """
                INSERT INTO menu_items (
                    id, tenant_id, menu_version_id, category_id, public_id, name,
                    price_minor, currency, sort_order
                ) VALUES (
                    '%s', '%s', '%s', '%s', 'pizza-cheese', 'Cheese pizza',
                    15000, 'RUB', 0
                )
                """.formatted(MENU_ITEM, OWNER, MENU_VERSION, CATEGORY));
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

    private static void preparePopulatedShadow(String schema, String sourceMutation) throws SQLException {
        resetSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
        try (var connection = connection(schema)) {
            insertCompleteOwnerGraph(connection);
            if (sourceMutation != null) {
                execute(connection, sourceMutation);
            }
        }
        flyway(schema, MigrationVersion.fromVersion("6")).migrate();
        try (var connection = connection(schema)) {
            execute(connection, "UPDATE sc_assets SET storage_state = 'canonical_ready' WHERE id = '" + ASSET + "'");
        }
    }

    private static void assertCutoverRejected(String schema, String diagnostic) throws SQLException {
        assertThatThrownBy(() -> flyway(schema, null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining(diagnostic);
        assertContractRolledBack(schema);
    }

    private static void assertContractRolledBack(String schema) throws SQLException {
        assertThat(currentVersion(schema)).isEqualTo("6");
        assertThat(tableExists(schema, "tenants")).isTrue();
        assertThat(tableExists(schema, "sc_site_configuration")).isTrue();
        assertThat(tableExists(schema, "site_configuration")).isFalse();
        assertThat(functionExists(schema, "sc_refresh_single_client_shadow")).isTrue();
    }

    private static void ensureRuntimeRole(String schema) throws SQLException {
        try (var connection = connection(schema)) {
            execute(connection, """
                    DO $role$
                    BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                            CREATE ROLE %s NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
                        END IF;
                    END
                    $role$;
                    REVOKE ALL ON SCHEMA %s FROM %s;
                    REVOKE ALL ON ALL TABLES IN SCHEMA %s FROM %s;
                    REVOKE ALL ON ALL SEQUENCES IN SCHEMA %s FROM %s;
                    REVOKE ALL ON ALL FUNCTIONS IN SCHEMA %s FROM %s;
                    """.formatted(
                    RUNTIME_ROLE, RUNTIME_ROLE,
                    schema, RUNTIME_ROLE,
                    schema, RUNTIME_ROLE,
                    schema, RUNTIME_ROLE,
                    schema, RUNTIME_ROLE));
        }
    }

    private static void assertRuntimeSqlRejected(String schema, String sql) throws SQLException {
        try (var runtime = connection(schema)) {
            execute(runtime, "SET ROLE " + RUNTIME_ROLE);
            assertThatThrownBy(() -> execute(runtime, sql)).isInstanceOf(SQLException.class);
        }
    }

    private static Flyway flyway(String schema, MigrationVersion target) {
        return flyway(schema, target, null);
    }

    private static Flyway approvedFlyway(String schema, String fingerprint, String runtimeRole) {
        var initSql = "SELECT set_config('app.sc_be_001_approved_source_fingerprint', '%s', false);"
                .formatted(fingerprint)
                + "SELECT set_config('app.sc_be_001_runtime_role', '%s', false);".formatted(runtimeRole);
        return flyway(schema, null, initSql);
    }

    private static Flyway runtimeRoleFlyway(String schema, String runtimeRole) {
        var initSql = "SELECT set_config('app.sc_be_001_runtime_role', '%s', false);".formatted(runtimeRole);
        return flyway(schema, null, initSql);
    }

    private static Flyway flyway(String schema, MigrationVersion target, String initSql) {
        var configuration = Flyway.configure()
                .dataSource(
                        PostgreSqlTestContainer.INSTANCE.getJdbcUrl(),
                        PostgreSqlTestContainer.INSTANCE.getUsername(),
                        PostgreSqlTestContainer.INSTANCE.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true);
        if (initSql != null) {
            configuration.initSql(initSql);
        }
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
        try (var statement = connection.createStatement()) {
            statement.execute("SET TIME ZONE 'UTC'");
        }
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

    private static void assertCleanFinalCatalog(String schema) throws SQLException {
        assertThat(queryLong(schema, """
                SELECT count(*)
                FROM pg_class object_record
                JOIN pg_namespace namespace ON namespace.oid = object_record.relnamespace
                WHERE namespace.nspname = current_schema()
                  AND object_record.relkind IN ('r', 'p', 'i', 'S')
                  AND (object_record.relname LIKE 'sc\\_%%' ESCAPE '\\'
                       OR lower(object_record.relname) LIKE '%%tenant%%')
                """)).isZero();
        assertThat(queryLong(schema, """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND lower(column_name) LIKE '%%tenant%%'
                """)).isZero();
        assertThat(queryLong(schema, """
                SELECT count(*)
                FROM pg_constraint constraint_record
                JOIN pg_class target ON target.oid = constraint_record.conrelid
                JOIN pg_namespace namespace ON namespace.oid = target.relnamespace
                WHERE namespace.nspname = current_schema()
                  AND (constraint_record.conname LIKE 'sc\\_%%' ESCAPE '\\'
                       OR constraint_record.conname LIKE '%%\\_sc\\_%%' ESCAPE '\\'
                       OR lower(constraint_record.conname) LIKE '%%tenant%%')
                """)).isZero();
    }

    private static long count(String schema, String table) throws SQLException {
        return queryLong(schema, "SELECT count(*) FROM " + table);
    }

    private static boolean tableExists(String schema, String table) throws SQLException {
        return relationExists(schema, table);
    }

    private static boolean indexExists(String schema, String index) throws SQLException {
        return relationExists(schema, index);
    }

    private static boolean relationExists(String schema, String relation) throws SQLException {
        try (var connection = connection(schema);
                var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, schema + "." + relation);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    private static boolean functionExists(String schema, String function) throws SQLException {
        return queryLong(schema, """
                SELECT count(*)
                FROM pg_proc function_record
                JOIN pg_namespace namespace ON namespace.oid = function_record.pronamespace
                WHERE namespace.nspname = current_schema()
                  AND function_record.proname = '%s'
                """.formatted(function)) > 0;
    }

    private static boolean columnExists(String schema, String table, String column) throws SQLException {
        return queryLong(schema, """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = '%s'
                  AND column_name = '%s'
                """.formatted(table, column)) > 0;
    }

    private static long queryLong(String schema, String sql) throws SQLException {
        try (var connection = connection(schema)) {
            return queryLong(connection, sql);
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static String queryString(String schema, String sql) throws SQLException {
        try (var connection = connection(schema)) {
            return queryString(connection, sql);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
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
