package com.salamu.menu.menu.internal.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.menu.api.PublishedMenuSnapshot;
import com.salamu.menu.menu.internal.application.DeterministicMenuIds.ExpectedMenuIds;
import com.salamu.menu.menu.internal.application.MenuProvisioningRepository;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantId;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

/** Fixed-query, tenant-scoped bulk persistence for the temporary provisioning port. */
@Repository
@org.springframework.context.annotation.Profile("legacy-import")
class JdbcMenuProvisioningRepository implements MenuProvisioningRepository {

    private static final String FIND_HEADER = """
            SELECT id, tenant_id, restaurant_id, version, status, published_at
              FROM menu_versions
             WHERE tenant_id = ?
               AND restaurant_id = ?
               AND version = ?
             ORDER BY id
            """;

    private static final String FIND_HEADER_FOR_UPDATE = FIND_HEADER + " FOR UPDATE";

    private static final String FIND_CATEGORIES = """
            SELECT id, public_id, name, description, image_asset_id, sort_order, available
              FROM menu_categories
             WHERE tenant_id = ?
               AND menu_version_id = ?
             ORDER BY sort_order, public_id
            """;

    private static final String FIND_CATEGORIES_FOR_UPDATE = FIND_CATEGORIES + " FOR UPDATE";

    private static final String FIND_ITEMS = """
            SELECT i.id,
                   i.category_id,
                   i.public_id,
                   c.public_id AS category_public_id,
                   i.name,
                   i.description,
                   i.price_minor,
                   i.currency,
                   i.image_asset_id,
                   i.available,
                   i.sort_order
              FROM menu_items i
              JOIN menu_categories c
                ON c.tenant_id = i.tenant_id
               AND c.menu_version_id = i.menu_version_id
               AND c.id = i.category_id
             WHERE i.tenant_id = ?
               AND i.menu_version_id = ?
             ORDER BY c.sort_order, c.public_id, i.sort_order, i.public_id
            """;

    private static final String FIND_ITEMS_FOR_UPDATE = FIND_ITEMS + " FOR UPDATE OF i";

    private static final String FIND_PUBLISHED_VERSIONS = """
            SELECT version
              FROM menu_versions
             WHERE tenant_id = ?
               AND restaurant_id = ?
               AND status = 'published'
             ORDER BY version
            """;

    private static final String INSERT_VERSION = """
            INSERT INTO menu_versions (
                id, tenant_id, restaurant_id, version, status, published_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'published', ?, ?, ?)
            """;

    private static final String INSERT_CATEGORY = """
            INSERT INTO menu_categories (
                id, tenant_id, menu_version_id, public_id, name, description,
                image_asset_id, sort_order, available
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_ITEM = """
            INSERT INTO menu_items (
                id, tenant_id, menu_version_id, category_id, public_id, name, description,
                price_minor, currency, image_asset_id, available, sort_order
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String DELETE_VERSION = """
            DELETE FROM menu_versions
             WHERE tenant_id = ?
               AND restaurant_id = ?
               AND version = ?
               AND id = ?
               AND status = 'published'
            """;

    private final JdbcOperations jdbcOperations;

    JdbcMenuProvisioningRepository(JdbcOperations jdbcOperations) {
        this.jdbcOperations = jdbcOperations;
    }

    @Override
    public Optional<StoredMenu> find(TenantId tenantId, UUID restaurantId, long version) {
        return find(tenantId, restaurantId, version, false);
    }

    @Override
    public Optional<StoredMenu> findForUpdate(TenantId tenantId, UUID restaurantId, long version) {
        return find(tenantId, restaurantId, version, true);
    }

    private Optional<StoredMenu> find(
            TenantId tenantId,
            UUID restaurantId,
            long version,
            boolean forUpdate) {
        var header = jdbcOperations.query(
                        forUpdate ? FIND_HEADER_FOR_UPDATE : FIND_HEADER,
                        JdbcMenuProvisioningRepository::mapHeader,
                        tenantId.value(),
                        restaurantId,
                        version)
                .stream()
                .findFirst();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        var value = header.orElseThrow();
        var categories = jdbcOperations.query(
                forUpdate ? FIND_CATEGORIES_FOR_UPDATE : FIND_CATEGORIES,
                JdbcMenuProvisioningRepository::mapCategory,
                tenantId.value(),
                value.menuVersionId());
        var items = jdbcOperations.query(
                forUpdate ? FIND_ITEMS_FOR_UPDATE : FIND_ITEMS,
                JdbcMenuProvisioningRepository::mapItem,
                tenantId.value(),
                value.menuVersionId());
        return Optional.of(new StoredMenu(value, categories, items));
    }

    @Override
    public List<Long> findPublishedVersions(TenantId tenantId, UUID restaurantId) {
        return jdbcOperations.query(
                FIND_PUBLISHED_VERSIONS,
                (resultSet, rowNumber) -> resultSet.getLong("version"),
                tenantId.value(),
                restaurantId);
    }

    @Override
    public void insert(PublishedMenuSnapshot snapshot, ExpectedMenuIds ids, Instant createdAt) {
        var created = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        jdbcOperations.update(
                INSERT_VERSION,
                ids.menuVersionId(),
                snapshot.tenantId().value(),
                snapshot.restaurantId(),
                snapshot.version(),
                OffsetDateTime.ofInstant(snapshot.publishedAt(), ZoneOffset.UTC),
                created,
                created);

        jdbcOperations.batchUpdate(INSERT_CATEGORY, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                var category = snapshot.categories().get(index);
                statement.setObject(1, ids.categoryIds().get(category.categoryId()));
                statement.setObject(2, snapshot.tenantId().value());
                statement.setObject(3, ids.menuVersionId());
                statement.setString(4, category.categoryId());
                statement.setString(5, category.name());
                setNullableString(statement, 6, category.description());
                setNullableUuid(statement, 7, assetId(category.imageAsset()));
                statement.setInt(8, category.sortOrder());
                statement.setBoolean(9, category.available());
            }

            @Override
            public int getBatchSize() {
                return snapshot.categories().size();
            }
        });

        jdbcOperations.batchUpdate(INSERT_ITEM, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                var item = snapshot.items().get(index);
                statement.setObject(1, ids.itemIds().get(item.menuItemId()));
                statement.setObject(2, snapshot.tenantId().value());
                statement.setObject(3, ids.menuVersionId());
                statement.setObject(4, ids.categoryIds().get(item.categoryId()));
                statement.setString(5, item.menuItemId());
                statement.setString(6, item.name());
                setNullableString(statement, 7, item.description());
                statement.setLong(8, item.priceMinor());
                statement.setString(9, item.currency());
                setNullableUuid(statement, 10, assetId(item.imageAsset()));
                statement.setBoolean(11, item.available());
                statement.setInt(12, item.sortOrder());
            }

            @Override
            public int getBatchSize() {
                return snapshot.items().size();
            }
        });
    }

    @Override
    public int delete(TenantId tenantId, UUID restaurantId, long version, UUID menuVersionId) {
        return jdbcOperations.update(
                DELETE_VERSION,
                tenantId.value(),
                restaurantId,
                version,
                menuVersionId);
    }

    private static StoredHeader mapHeader(ResultSet resultSet, int rowNumber) throws SQLException {
        var publishedAt = resultSet.getObject("published_at", OffsetDateTime.class);
        return new StoredHeader(
                resultSet.getObject("id", UUID.class),
                new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                resultSet.getObject("restaurant_id", UUID.class),
                resultSet.getLong("version"),
                resultSet.getString("status"),
                publishedAt == null ? null : publishedAt.toInstant());
    }

    private static StoredCategory mapCategory(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredCategory(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("public_id"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                assetId(resultSet, "image_asset_id"),
                resultSet.getInt("sort_order"),
                resultSet.getBoolean("available"));
    }

    private static StoredItem mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredItem(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("category_id", UUID.class),
                resultSet.getString("public_id"),
                resultSet.getString("category_public_id"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                resultSet.getLong("price_minor"),
                resultSet.getString("currency"),
                assetId(resultSet, "image_asset_id"),
                resultSet.getBoolean("available"),
                resultSet.getInt("sort_order"));
    }

    private static TenantAssetId assetId(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column, UUID.class);
        return value == null ? null : new TenantAssetId(value);
    }

    private static TenantAssetId assetId(com.salamu.menu.menu.api.MenuAssetReference reference) {
        return reference == null ? null : reference.assetId();
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableUuid(
            PreparedStatement statement,
            int index,
            TenantAssetId value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.OTHER);
        } else {
            statement.setObject(index, value.value());
        }
    }
}
