package com.salamu.menu.restaurant.internal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.restaurant.api.DefaultRestaurantSnapshot.RestaurantStatus;
import com.salamu.menu.restaurant.api.DefaultRestaurantSpec;
import com.salamu.menu.restaurant.api.RestaurantProvisioningException;
import com.salamu.menu.restaurant.api.RestaurantProvisioningException.Code;
import com.salamu.menu.restaurant.internal.application.RestaurantProvisioningRepository;
import com.salamu.menu.tenancy.api.TenantId;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@org.springframework.context.annotation.Profile("legacy-import")
class JdbcRestaurantProvisioningRepository implements RestaurantProvisioningRepository {

    private static final String SELECT = """
            SELECT id, tenant_id, slug, display_name, status, is_default
              FROM restaurants
             WHERE tenant_id = :tenantId
               AND %s
            """;

    private final JdbcClient jdbcClient;

    JdbcRestaurantProvisioningRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<StoredRestaurant> findDefault(TenantId tenantId) {
        return query(tenantId, "is_default = true", null, false);
    }

    @Override
    public Optional<StoredRestaurant> findDefaultForUpdate(TenantId tenantId) {
        return query(tenantId, "is_default = true", null, true);
    }

    @Override
    public Optional<StoredRestaurant> findByNaturalKey(TenantId tenantId, String restaurantSlug) {
        return query(tenantId, "slug = :slug", restaurantSlug, false);
    }

    @Override
    public Optional<StoredRestaurant> findByNaturalKeyForUpdate(TenantId tenantId, String restaurantSlug) {
        return query(tenantId, "slug = :slug", restaurantSlug, true);
    }

    @Override
    public Optional<StoredRestaurant> findByIdForUpdate(TenantId tenantId, UUID restaurantId) {
        return jdbcClient.sql(SELECT.formatted("id = :restaurantId") + " FOR UPDATE")
                .param("tenantId", tenantId.value())
                .param("restaurantId", restaurantId)
                .query(this::map)
                .optional();
    }

    private Optional<StoredRestaurant> query(
            TenantId tenantId,
            String predicate,
            String restaurantSlug,
            boolean forUpdate) {
        var sql = SELECT.formatted(predicate);
        if (forUpdate) {
            sql += " FOR UPDATE";
        }
        var statement = jdbcClient.sql(sql).param("tenantId", tenantId.value());
        if (restaurantSlug != null) {
            statement = statement.param("slug", restaurantSlug);
        }
        return statement.query(this::map).optional();
    }

    @Override
    public void insertDefault(UUID restaurantId, DefaultRestaurantSpec spec, OffsetDateTime now) {
        var affected = jdbcClient.sql("""
                INSERT INTO restaurants (
                    id, tenant_id, slug, display_name, status, is_default, created_at, updated_at
                ) VALUES (
                    :restaurantId, :tenantId, :slug, :displayName, 'active', true, :now, :now
                )
                """)
                .param("restaurantId", restaurantId)
                .param("tenantId", spec.tenantId().value())
                .param("slug", spec.restaurantSlug())
                .param("displayName", spec.displayName())
                .param("now", now)
                .update();
        if (affected != 1) {
            throw new RestaurantProvisioningException(Code.PERSISTED_STATE_INVALID);
        }
    }

    @Override
    public boolean deleteExactDefault(TenantId tenantId, UUID restaurantId, String restaurantSlug) {
        return jdbcClient.sql("""
                DELETE FROM restaurants
                 WHERE tenant_id = :tenantId
                   AND id = :restaurantId
                   AND slug = :slug
                   AND is_default = true
                """)
                .param("tenantId", tenantId.value())
                .param("restaurantId", restaurantId)
                .param("slug", restaurantSlug)
                .update() == 1;
    }

    private StoredRestaurant map(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            return new StoredRestaurant(
                    resultSet.getObject("id", UUID.class),
                    new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                    resultSet.getString("slug"),
                    resultSet.getString("display_name"),
                    RestaurantStatus.fromDatabaseValue(resultSet.getString("status")),
                    resultSet.getBoolean("is_default"));
        } catch (IllegalArgumentException exception) {
            throw new RestaurantProvisioningException(Code.PERSISTED_STATE_INVALID);
        }
    }
}
