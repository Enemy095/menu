package com.salamu.menu.menu.internal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.menu.internal.application.PublicMenuReadRepository;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantId;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

/** Explicit JDBC projection with three fixed tenant-scoped queries and no entity graph. */
@Repository
class JdbcPublicMenuReadRepository implements PublicMenuReadRepository {

    private static final String CURRENT_PUBLISHED_MENU = """
            SELECT mv.id AS menu_version_id,
                   r.id AS restaurant_id,
                   mv.version,
                   mv.published_at,
                   t.currency
            FROM tenants t
            JOIN restaurants r
              ON r.tenant_id = t.id
             AND r.status = 'active'
             AND r.is_default = true
            JOIN menu_versions mv
              ON mv.tenant_id = t.id
             AND mv.restaurant_id = r.id
             AND mv.status = 'published'
            WHERE t.id = ?
              AND t.status = 'active'
            ORDER BY mv.version DESC
            LIMIT 1
            """;

    private static final String PUBLISHED_CATEGORIES = """
            SELECT c.public_id,
                   c.name,
                   c.description,
                   c.image_asset_id,
                   c.sort_order
            FROM menu_categories c
            WHERE c.tenant_id = ?
              AND c.menu_version_id = ?
              AND c.available = true
            ORDER BY c.sort_order, c.public_id
            """;

    private static final String PUBLISHED_ITEMS = """
            SELECT i.public_id AS item_public_id,
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
             AND c.available = true
            WHERE i.tenant_id = ?
              AND i.menu_version_id = ?
            ORDER BY c.sort_order, c.public_id, i.sort_order, i.public_id
            """;

    private final JdbcOperations jdbcOperations;

    JdbcPublicMenuReadRepository(JdbcOperations jdbcOperations) {
        this.jdbcOperations = jdbcOperations;
    }

    @Override
    public Optional<MenuHeader> findCurrentPublished(TenantId tenantId) {
        return jdbcOperations.query(
                        CURRENT_PUBLISHED_MENU,
                        JdbcPublicMenuReadRepository::mapHeader,
                        tenantId.value())
                .stream()
                .findFirst();
    }

    @Override
    public List<MenuCategoryRow> findCategories(TenantId tenantId, UUID menuVersionId) {
        return jdbcOperations.query(
                PUBLISHED_CATEGORIES,
                JdbcPublicMenuReadRepository::mapCategory,
                tenantId.value(),
                menuVersionId);
    }

    @Override
    public List<MenuItemRow> findItems(TenantId tenantId, UUID menuVersionId) {
        return jdbcOperations.query(
                PUBLISHED_ITEMS,
                JdbcPublicMenuReadRepository::mapItem,
                tenantId.value(),
                menuVersionId);
    }

    private static MenuHeader mapHeader(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MenuHeader(
                resultSet.getObject("menu_version_id", UUID.class),
                resultSet.getObject("restaurant_id", UUID.class),
                resultSet.getLong("version"),
                resultSet.getObject("published_at", OffsetDateTime.class).toInstant(),
                resultSet.getString("currency"));
    }

    private static MenuCategoryRow mapCategory(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MenuCategoryRow(
                resultSet.getString("public_id"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                assetId(resultSet, "image_asset_id"),
                resultSet.getInt("sort_order"));
    }

    private static MenuItemRow mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MenuItemRow(
                resultSet.getString("item_public_id"),
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
}
