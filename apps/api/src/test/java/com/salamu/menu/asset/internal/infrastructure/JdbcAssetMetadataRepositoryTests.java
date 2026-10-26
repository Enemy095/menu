package com.salamu.menu.asset.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.salamu.menu.asset.api.AssetId;
import com.salamu.menu.asset.api.AssetUnavailableException;

class JdbcAssetMetadataRepositoryTests {

    @Test
    void emptyInputPerformsNoDatabaseOperation() {
        NamedParameterJdbcOperations operations = mock(NamedParameterJdbcOperations.class);
        JdbcAssetMetadataRepository repository = new JdbcAssetMetadataRepository(operations);

        assertThat(repository.findAllActiveByIds(Set.of())).isEmpty();
        verifyNoInteractions(operations);
    }

    @Test
    void corruptRowFailsWithRedactedDomainError() throws Exception {
        NamedParameterJdbcOperations operations = mock(NamedParameterJdbcOperations.class);
        ResultSet row = activeRow();
        when(row.getString("object_key")).thenReturn("invalid/private/key.png");
        when(operations.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(row, 0));
                });
        JdbcAssetMetadataRepository repository = new JdbcAssetMetadataRepository(operations);
        AssetId id = new AssetId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        assertThatThrownBy(() -> repository.findAllActiveByIds(Set.of(id)))
                .isInstanceOf(AssetUnavailableException.class)
                .hasMessage("Required asset is unavailable")
                .hasMessageNotContaining(id.value().toString())
                .hasMessageNotContaining("invalid/private/key.png");
    }

    private static ResultSet activeRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("status")).thenReturn("active");
        when(row.getString("asset_type")).thenReturn("logo");
        when(row.getString("object_key")).thenReturn("assets/logo/" + "a".repeat(64) + ".png");
        when(row.getString("content_type")).thenReturn("image/png");
        when(row.getString("sha256")).thenReturn("a".repeat(64));
        when(row.getLong("size_bytes")).thenReturn(8L);
        when(row.wasNull()).thenReturn(false);
        when(row.getObject("id", UUID.class))
                .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(row.getObject("created_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        return row;
    }
}
