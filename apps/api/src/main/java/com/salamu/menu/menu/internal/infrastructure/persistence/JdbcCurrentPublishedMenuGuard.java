package com.salamu.menu.menu.internal.infrastructure.persistence;

import com.salamu.menu.menu.api.CurrentPublishedMenuGuard;
import com.salamu.menu.menu.api.MenuVersionId;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCurrentPublishedMenuGuard implements CurrentPublishedMenuGuard {

    private static final String LOCK_CURRENT = """
            SELECT menu_version.id
              FROM menu_versions menu_version
              JOIN restaurants restaurant ON restaurant.id = menu_version.restaurant_id
             WHERE menu_version.id = :menuVersionId
               AND menu_version.version = :version
               AND menu_version.status = 'published'
               AND restaurant.status = 'active'
               AND restaurant.is_default
             FOR SHARE OF menu_version
            """;

    private final JdbcClient jdbc;

    JdbcCurrentPublishedMenuGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean lockCurrent(MenuVersionId menuVersionId, long version) {
        if (menuVersionId == null || version < 1) {
            throw new IllegalArgumentException("menu version reference is invalid");
        }
        return jdbc.sql(LOCK_CURRENT)
                .param("menuVersionId", menuVersionId.value())
                .param("version", version)
                .query(java.util.UUID.class)
                .optional()
                .isPresent();
    }
}
