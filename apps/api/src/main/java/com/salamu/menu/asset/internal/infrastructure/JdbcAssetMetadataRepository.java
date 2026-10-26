package com.salamu.menu.asset.internal.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

import com.salamu.menu.asset.api.AssetId;
import com.salamu.menu.asset.api.AssetKind;
import com.salamu.menu.asset.api.AssetUnavailableException;
import com.salamu.menu.asset.internal.application.AssetCatalogService;
import com.salamu.menu.asset.internal.application.AssetKindStorageName;
import com.salamu.menu.asset.internal.application.AssetMetadataRepository;
import com.salamu.menu.asset.internal.application.AssetObjectKey;
import com.salamu.menu.asset.internal.application.AssetRecord;
import com.salamu.menu.asset.internal.application.AssetSha256;
import com.salamu.menu.asset.internal.application.AssetStatus;

class JdbcAssetMetadataRepository implements AssetMetadataRepository {

    private static final String FIND_ACTIVE_BY_IDS = """
            SELECT id, object_key, asset_type, content_type, size_bytes, sha256, status, created_at
            FROM assets
            WHERE status = 'active'
              AND id IN (:ids)
            """;

    private final NamedParameterJdbcOperations jdbcTemplate;

    JdbcAssetMetadataRepository(JdbcTemplate jdbcTemplate) {
        this(new NamedParameterJdbcTemplate(Objects.requireNonNull(jdbcTemplate, "jdbcTemplate")));
    }

    JdbcAssetMetadataRepository(NamedParameterJdbcOperations jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public List<AssetRecord> findAllActiveByIds(Set<AssetId> ids) {
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > AssetCatalogService.MAX_BATCH_SIZE || ids.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Asset ID batch must contain 1 to 500 non-null IDs");
        }

        List<UUID> databaseIds = ids.stream().map(AssetId::value).toList();
        MapSqlParameterSource parameters = new MapSqlParameterSource("ids", databaseIds);
        return List.copyOf(jdbcTemplate.query(FIND_ACTIVE_BY_IDS, parameters, this::mapRow));
    }

    private AssetRecord mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            String status = requiredString(resultSet, "status");
            if (!"active".equals(status)) {
                throw unavailable();
            }
            AssetKind kind = AssetKindStorageName.fromStorageName(requiredString(resultSet, "asset_type"));
            OffsetDateTime createdAt = resultSet.getObject("created_at", OffsetDateTime.class);
            if (createdAt == null) {
                throw unavailable();
            }
            return new AssetRecord(
                    new AssetId(resultSet.getObject("id", UUID.class)),
                    kind,
                    new AssetObjectKey(requiredString(resultSet, "object_key")),
                    requiredString(resultSet, "content_type"),
                    requiredLong(resultSet, "size_bytes"),
                    new AssetSha256(requiredString(resultSet, "sha256")),
                    AssetStatus.ACTIVE,
                    createdAt.toInstant());
        } catch (AssetUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private static String requiredString(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        if (value == null || value.isBlank()) {
            throw unavailable();
        }
        return value;
    }

    private static long requiredLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        if (resultSet.wasNull()) {
            throw unavailable();
        }
        return value;
    }

    private static AssetUnavailableException unavailable() {
        return new AssetUnavailableException();
    }
}
