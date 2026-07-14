package com.salamu.menu.restaurant.internal.infrastructure.persistence;

import java.util.List;

import com.salamu.menu.restaurant.api.RestaurantId;
import com.salamu.menu.restaurant.api.RestaurantStatus;
import com.salamu.menu.restaurant.internal.application.DefaultRestaurantRepository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDefaultRestaurantRepository implements DefaultRestaurantRepository {

    private static final String ACTIVE_DEFAULTS_QUERY = """
            SELECT id, external_key, display_name, status
              FROM restaurants
             WHERE is_default = true
               AND status = 'active'
             ORDER BY id
             LIMIT 2
            """;

    private final JdbcClient jdbcClient;

    JdbcDefaultRestaurantRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<StoredRestaurant> findActiveDefaults() {
        return jdbcClient.sql(ACTIVE_DEFAULTS_QUERY)
                .query((resultSet, rowNumber) -> new StoredRestaurant(
                        new RestaurantId(resultSet.getObject("id", java.util.UUID.class)),
                        resultSet.getString("external_key"),
                        resultSet.getString("display_name"),
                        RestaurantStatus.valueOf(resultSet.getString("status").toUpperCase(java.util.Locale.ROOT))))
                .list();
    }
}
