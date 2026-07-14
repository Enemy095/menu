package com.salamu.menu.dataimport.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.salamu.menu.dataimport.api.DataImportConflictException;

import org.springframework.jdbc.core.JdbcTemplate;

final class StambulContentGraph {

    private static final long IMPORT_ADVISORY_LOCK = 7_459_321_001L;
    private static final Map<String, Integer> EXPECTED_CONTENT_COUNTS = Map.ofEntries(
            Map.entry("site_configuration", 1),
            Map.entry("assets", 1),
            Map.entry("site_branding", 1),
            Map.entry("site_profile", 1),
            Map.entry("site_features", 13),
            Map.entry("restaurants", 1),
            Map.entry("menu_versions", 1),
            Map.entry("menu_categories", 4),
            Map.entry("menu_items", 23),
            Map.entry("menu_modifier_groups", 0),
            Map.entry("menu_modifier_options", 0));
    private static final List<String> RUNTIME_TABLES = List.of(
            "notification_destinations",
            "order_counter",
            "orders",
            "order_items",
            "order_status_history",
            "outbox_events",
            "notification_deliveries");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    StambulContentGraph(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    void lockPublication() {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(?)",
                preparedStatement -> preparedStatement.setLong(1, IMPORT_ADVISORY_LOCK),
                resultSet -> null);
    }

    State inspect(PreparedStambulImport prepared) {
        long contentRows = EXPECTED_CONTENT_COUNTS.keySet().stream().mapToLong(this::count).sum();
        if (contentRows == 0) {
            boolean runtimeEmpty = RUNTIME_TABLES.stream().allMatch(table -> count(table) == 0);
            return runtimeEmpty ? State.EMPTY : State.CONFLICT;
        }
        if (!expectedCountsMatch()) {
            return State.CONFLICT;
        }
        return exactRowsMatch(prepared) ? State.MATCH : State.CONFLICT;
    }

    void insert(PreparedStambulImport prepared) {
        requireState(inspect(prepared) == State.EMPTY, "Target database is not empty");
        var bundle = prepared.bundle();
        var site = bundle.site();
        var profile = site.profile();
        var asset = prepared.assetReceipt();

        jdbc.update("""
                INSERT INTO assets (
                    id, object_key, asset_type, content_type, size_bytes, sha256, status
                ) VALUES (?, ?, ?, ?, ?, ?, 'active')
                """,
                prepared.assetId(),
                asset.objectKey(),
                "logo",
                asset.contentType(),
                asset.sizeBytes(),
                asset.sha256());
        jdbc.update("""
                INSERT INTO site_configuration (
                    singleton_id, status, locale, currency, timezone
                ) VALUES (1, 'active', ?, ?, ?)
                """,
                bundle.localTestBaseline().locale(),
                bundle.localTestBaseline().currency(),
                prepared.timezone());
        jdbc.update("""
                INSERT INTO site_branding (
                    singleton_id, display_name, logo_alt, business_type, logo_asset_id, favicon_asset_id,
                    design_tokens, localized_texts, hero, seo_metadata
                ) VALUES (1, ?, ?, ?, ?, NULL, CAST(? AS jsonb), CAST(? AS jsonb),
                    CAST(? AS jsonb), CAST(? AS jsonb))
                """,
                site.displayName(),
                bundle.asset().altText(),
                site.businessType(),
                prepared.assetId(),
                json(site.designTokens()),
                json(site.localizedTexts()),
                json(site.hero()),
                seoJson(prepared));
        jdbc.update("""
                INSERT INTO site_profile (
                    singleton_id, public_phone, public_phone_label, public_email, address_text,
                    working_hours, working_hours_text, latitude, longitude, social_links
                ) VALUES (1, ?, ?, ?, ?, CAST(? AS jsonb), ?, NULL, NULL, CAST(? AS jsonb))
                """,
                profile.publicPhone(),
                profile.publicPhoneLabel(),
                profile.publicEmail(),
                profile.address(),
                json(profile.workingHours()),
                profile.workingHoursText(),
                json(profile.socialLinks()));
        for (var feature : site.features()) {
            jdbc.update("""
                    INSERT INTO site_features (feature_key, enabled, config)
                    VALUES (?, ?, CAST(? AS jsonb))
                    """, feature.key(), feature.enabled(), json(feature.config()));
        }
        jdbc.update("""
                INSERT INTO restaurants (
                    id, external_key, slug, display_name, status, is_default
                ) VALUES (?, ?, ?, ?, 'active', true)
                """,
                prepared.restaurantId(),
                bundle.restaurant().externalKey(),
                bundle.restaurant().slug(),
                bundle.restaurant().displayName());
        jdbc.update("""
                INSERT INTO menu_versions (
                    id, restaurant_id, version, status, published_at, currency
                ) VALUES (?, ?, ?, 'published', ?, ?)
                """,
                prepared.menuVersionId(),
                prepared.restaurantId(),
                bundle.menu().version(),
                Timestamp.from(Instant.parse(bundle.menu().publishedAt())),
                bundle.menu().currency());
        int categoryOrder = 0;
        for (var category : bundle.menu().categories()) {
            UUID categoryId = prepared.categoryIds().get(category.publicId());
            jdbc.update("""
                    INSERT INTO menu_categories (
                        id, menu_version_id, public_id, name, description,
                        image_asset_id, sort_order, available
                    ) VALUES (?, ?, ?, ?, NULL, NULL, ?, true)
                    """,
                    categoryId,
                    prepared.menuVersionId(),
                    category.publicId(),
                    category.name(),
                    categoryOrder);
            int itemOrder = 0;
            for (var item : category.items()) {
                jdbc.update("""
                        INSERT INTO menu_items (
                            id, menu_version_id, category_id, public_id, name, description,
                            price_minor, currency, image_asset_id, available, sort_order
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, true, ?)
                        """,
                        prepared.itemIds().get(item.publicId()),
                        prepared.menuVersionId(),
                        categoryId,
                        item.publicId(),
                        item.name(),
                        item.description(),
                        item.priceMinor(),
                        bundle.menu().currency(),
                        itemOrder);
                itemOrder++;
            }
            categoryOrder++;
        }
        requireState(inspect(prepared) == State.MATCH, "Inserted graph does not match approved bundle");
    }

    private boolean exactRowsMatch(PreparedStambulImport prepared) {
        var bundle = prepared.bundle();
        var site = bundle.site();
        var profile = site.profile();
        var asset = prepared.assetReceipt();
        if (!matches("""
                SELECT count(*) FROM assets
                 WHERE id = ? AND object_key = ? AND asset_type = 'logo'
                   AND content_type = ? AND size_bytes = ? AND sha256 = ? AND status = 'active'
                """, prepared.assetId(), asset.objectKey(), asset.contentType(), asset.sizeBytes(), asset.sha256())
                || !matches("""
                SELECT count(*) FROM site_configuration
                 WHERE singleton_id = 1 AND status = 'active'
                   AND locale = ? AND currency = ? AND timezone = ?
                """, bundle.localTestBaseline().locale(), bundle.localTestBaseline().currency(), prepared.timezone())
                || !matches("""
                SELECT count(*) FROM site_branding
                 WHERE singleton_id = 1 AND display_name = ? AND logo_alt = ? AND business_type = ?
                   AND logo_asset_id = ? AND favicon_asset_id IS NULL
                   AND design_tokens = CAST(? AS jsonb)
                   AND localized_texts = CAST(? AS jsonb)
                   AND hero = CAST(? AS jsonb)
                   AND seo_metadata = CAST(? AS jsonb)
                """, site.displayName(), bundle.asset().altText(), site.businessType(), prepared.assetId(),
                        json(site.designTokens()), json(site.localizedTexts()), json(site.hero()), seoJson(prepared))
                || !matches("""
                SELECT count(*) FROM site_profile
                 WHERE singleton_id = 1 AND public_phone = ? AND public_phone_label = ?
                   AND public_email IS NOT DISTINCT FROM ? AND address_text IS NOT DISTINCT FROM ?
                   AND working_hours = CAST(? AS jsonb) AND working_hours_text = ?
                   AND latitude IS NULL AND longitude IS NULL AND social_links = CAST(? AS jsonb)
                """, profile.publicPhone(), profile.publicPhoneLabel(), profile.publicEmail(), profile.address(),
                        json(profile.workingHours()), profile.workingHoursText(), json(profile.socialLinks()))
                || !matches("""
                SELECT count(*) FROM restaurants
                 WHERE id = ? AND external_key = ? AND slug = ? AND display_name = ?
                   AND status = 'active' AND is_default = true
                """, prepared.restaurantId(), bundle.restaurant().externalKey(), bundle.restaurant().slug(),
                        bundle.restaurant().displayName())
                || !matches("""
                SELECT count(*) FROM menu_versions
                 WHERE id = ? AND restaurant_id = ? AND version = ? AND status = 'published'
                   AND published_at = ? AND currency = ?
                """, prepared.menuVersionId(), prepared.restaurantId(), bundle.menu().version(),
                        Timestamp.from(Instant.parse(bundle.menu().publishedAt())), bundle.menu().currency())) {
            return false;
        }
        for (var feature : site.features()) {
            if (!matches("""
                    SELECT count(*) FROM site_features
                     WHERE feature_key = ? AND enabled = ? AND config = CAST(? AS jsonb)
                    """, feature.key(), feature.enabled(), json(feature.config()))) {
                return false;
            }
        }
        int categoryOrder = 0;
        for (var category : bundle.menu().categories()) {
            UUID categoryId = prepared.categoryIds().get(category.publicId());
            if (!matches("""
                    SELECT count(*) FROM menu_categories
                     WHERE id = ? AND menu_version_id = ? AND public_id = ? AND name = ?
                       AND description IS NULL AND image_asset_id IS NULL
                       AND sort_order = ? AND available = true
                    """, categoryId, prepared.menuVersionId(), category.publicId(), category.name(), categoryOrder)) {
                return false;
            }
            int itemOrder = 0;
            for (var item : category.items()) {
                if (!matches("""
                        SELECT count(*) FROM menu_items
                         WHERE id = ? AND menu_version_id = ? AND category_id = ?
                           AND public_id = ? AND name = ? AND description = ?
                           AND price_minor = ? AND currency = ? AND image_asset_id IS NULL
                           AND available = true AND sort_order = ?
                        """, prepared.itemIds().get(item.publicId()), prepared.menuVersionId(), categoryId,
                        item.publicId(), item.name(), item.description(), item.priceMinor(),
                        bundle.menu().currency(), itemOrder)) {
                    return false;
                }
                itemOrder++;
            }
            categoryOrder++;
        }
        return true;
    }

    private boolean expectedCountsMatch() {
        return EXPECTED_CONTENT_COUNTS.entrySet().stream()
                .allMatch(entry -> count(entry.getKey()) == entry.getValue());
    }

    private long count(String table) {
        if (!EXPECTED_CONTENT_COUNTS.containsKey(table) && !RUNTIME_TABLES.contains(table)) {
            throw new IllegalArgumentException("Table is not part of the import safety allowlist");
        }
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return value == null ? -1 : value;
    }

    private boolean matches(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value != null && value == 1;
    }

    private String seoJson(PreparedStambulImport prepared) {
        var seo = objectMapper.createObjectNode();
        seo.put("title", prepared.bundle().site().seo().title());
        seo.put("description", prepared.bundle().site().seo().description());
        seo.put("canonicalUrl", prepared.canonicalUrl().toString());
        return json(seo);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Approved bundle contains invalid JSON", exception);
        }
    }

    private static void requireState(boolean condition, String message) {
        if (!condition) {
            throw new DataImportConflictException(message);
        }
    }

    enum State {
        EMPTY,
        MATCH,
        CONFLICT
    }
}
