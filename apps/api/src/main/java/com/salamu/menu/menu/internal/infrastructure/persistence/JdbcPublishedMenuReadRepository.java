package com.salamu.menu.menu.internal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.salamu.menu.asset.api.AssetId;
import com.salamu.menu.menu.api.MenuItemId;
import com.salamu.menu.menu.api.MenuVersionId;
import com.salamu.menu.menu.internal.application.PublishedMenuReadRepository;
import com.salamu.menu.restaurant.api.RestaurantId;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

/** Five ordered menu queries regardless of category, item, group, or option cardinality. */
@Repository
class JdbcPublishedMenuReadRepository implements PublishedMenuReadRepository {

    private static final String PUBLISHED_MENU = """
            SELECT mv.id,
                   mv.restaurant_id,
                   mv.version,
                   mv.published_at,
                   mv.currency
              FROM menu_versions mv
             WHERE mv.restaurant_id = ?
               AND mv.status = 'published'
             ORDER BY mv.version DESC, mv.id
             LIMIT 2
            """;

    private static final String CATEGORIES = """
            SELECT c.id,
                   c.public_id,
                   c.name,
                   c.description,
                   c.image_asset_id,
                   c.sort_order
              FROM menu_categories c
             WHERE c.menu_version_id = ?
               AND c.available = true
             ORDER BY c.sort_order, c.public_id
             LIMIT 501
            """;

    private static final String ITEMS = """
            SELECT i.id,
                   i.category_id,
                   c.public_id AS category_public_id,
                   i.public_id,
                   i.name,
                   i.description,
                   i.price_minor,
                   i.currency,
                   i.image_asset_id,
                   i.available,
                   i.sort_order
              FROM menu_items i
              JOIN menu_categories c
                ON c.menu_version_id = i.menu_version_id
               AND c.id = i.category_id
               AND c.available = true
             WHERE i.menu_version_id = ?
             ORDER BY c.sort_order, c.public_id, i.sort_order, i.public_id
             LIMIT 10001
            """;

    private static final String MODIFIER_GROUPS = """
            SELECT g.id,
                   g.menu_item_id,
                   g.public_id,
                   g.name,
                   g.min_selections,
                   g.max_selections,
                   g.sort_order
              FROM menu_modifier_groups g
              JOIN menu_items i
                ON i.menu_version_id = g.menu_version_id
               AND i.id = g.menu_item_id
              JOIN menu_categories c
                ON c.menu_version_id = i.menu_version_id
               AND c.id = i.category_id
               AND c.available = true
             WHERE g.menu_version_id = ?
             ORDER BY c.sort_order, c.public_id,
                      i.sort_order, i.public_id,
                      g.sort_order, g.public_id
             LIMIT 200001
            """;

    private static final String MODIFIER_OPTIONS = """
            SELECT o.id,
                   o.modifier_group_id,
                   o.public_id,
                   o.name,
                   o.price_delta_minor,
                   o.available,
                   o.sort_order
              FROM menu_modifier_options o
              JOIN menu_modifier_groups g
                ON g.menu_version_id = o.menu_version_id
               AND g.id = o.modifier_group_id
              JOIN menu_items i
                ON i.menu_version_id = g.menu_version_id
               AND i.id = g.menu_item_id
              JOIN menu_categories c
                ON c.menu_version_id = i.menu_version_id
               AND c.id = i.category_id
               AND c.available = true
             WHERE o.menu_version_id = ?
             ORDER BY c.sort_order, c.public_id,
                      i.sort_order, i.public_id,
                      g.sort_order, g.public_id,
                      o.sort_order, o.public_id
             LIMIT 20000001
            """;

    private final JdbcOperations jdbcOperations;

    JdbcPublishedMenuReadRepository(JdbcOperations jdbcOperations) {
        this.jdbcOperations = jdbcOperations;
    }

    @Override
    public List<MenuHeader> findPublished(RestaurantId restaurantId) {
        return jdbcOperations.query(PUBLISHED_MENU, JdbcPublishedMenuReadRepository::mapHeader, restaurantId.value());
    }

    @Override
    public List<CategoryRow> findCategories(MenuVersionId menuVersionId) {
        return jdbcOperations.query(CATEGORIES, JdbcPublishedMenuReadRepository::mapCategory, menuVersionId.value());
    }

    @Override
    public List<ItemRow> findItems(MenuVersionId menuVersionId) {
        return jdbcOperations.query(ITEMS, JdbcPublishedMenuReadRepository::mapItem, menuVersionId.value());
    }

    @Override
    public List<ModifierGroupRow> findModifierGroups(MenuVersionId menuVersionId) {
        return jdbcOperations.query(
                MODIFIER_GROUPS,
                JdbcPublishedMenuReadRepository::mapModifierGroup,
                menuVersionId.value());
    }

    @Override
    public List<ModifierOptionRow> findModifierOptions(MenuVersionId menuVersionId) {
        return jdbcOperations.query(
                MODIFIER_OPTIONS,
                JdbcPublishedMenuReadRepository::mapModifierOption,
                menuVersionId.value());
    }

    private static MenuHeader mapHeader(ResultSet resultSet, int rowNumber) throws SQLException {
        var publishedAt = resultSet.getObject("published_at", OffsetDateTime.class);
        return new MenuHeader(
                new MenuVersionId(resultSet.getObject("id", UUID.class)),
                new RestaurantId(resultSet.getObject("restaurant_id", UUID.class)),
                resultSet.getLong("version"),
                publishedAt == null ? null : publishedAt.toInstant(),
                resultSet.getString("currency"));
    }

    private static CategoryRow mapCategory(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CategoryRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("public_id"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                assetId(resultSet),
                resultSet.getInt("sort_order"));
    }

    private static ItemRow mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ItemRow(
                new MenuItemId(resultSet.getObject("id", UUID.class)),
                resultSet.getObject("category_id", UUID.class),
                resultSet.getString("category_public_id"),
                resultSet.getString("public_id"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                resultSet.getLong("price_minor"),
                resultSet.getString("currency"),
                assetId(resultSet),
                resultSet.getBoolean("available"),
                resultSet.getInt("sort_order"));
    }

    private static ModifierGroupRow mapModifierGroup(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ModifierGroupRow(
                resultSet.getObject("id", UUID.class),
                new MenuItemId(resultSet.getObject("menu_item_id", UUID.class)),
                resultSet.getString("public_id"),
                resultSet.getString("name"),
                resultSet.getInt("min_selections"),
                resultSet.getInt("max_selections"),
                resultSet.getInt("sort_order"));
    }

    private static ModifierOptionRow mapModifierOption(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ModifierOptionRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("modifier_group_id", UUID.class),
                resultSet.getString("public_id"),
                resultSet.getString("name"),
                resultSet.getLong("price_delta_minor"),
                resultSet.getBoolean("available"),
                resultSet.getInt("sort_order"));
    }

    private static AssetId assetId(ResultSet resultSet) throws SQLException {
        var value = resultSet.getObject("image_asset_id", UUID.class);
        return value == null ? null : new AssetId(value);
    }
}
