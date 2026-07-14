package com.salamu.menu.notification.internal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.api.NotificationDestinationSpec;
import com.salamu.menu.notification.api.NotificationDestinationStatus;
import com.salamu.menu.notification.api.NotificationProvisioningException;
import com.salamu.menu.notification.api.NotificationProvisioningException.Code;
import com.salamu.menu.notification.api.OpaqueSecretReference;
import com.salamu.menu.notification.internal.application.NotificationProvisioningRepository;
import com.salamu.menu.tenancy.api.TenantId;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@org.springframework.context.annotation.Profile("legacy-import")
class JdbcNotificationProvisioningRepository implements NotificationProvisioningRepository {

    private static final String SELECT = """
            SELECT id, tenant_id, channel, status, display_name, secret_reference,
                   metadata ->> 'referenceFingerprint' AS metadata_reference_fingerprint,
                   metadata ->> 'referenceScheme' AS metadata_reference_scheme,
                   metadata ->> 'readiness' AS metadata_readiness
              FROM notification_destinations
             WHERE tenant_id = :tenantId
               AND %s
            """;

    private final JdbcClient jdbcClient;

    JdbcNotificationProvisioningRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<StoredDestination> findAll(TenantId tenantId) {
        return jdbcClient.sql(SELECT.formatted("true") + " ORDER BY channel, display_name, id")
                .param("tenantId", tenantId.value())
                .query(this::map)
                .list();
    }

    @Override
    public Optional<StoredDestination> findByNaturalKey(
            TenantId tenantId,
            NotificationChannel channel,
            String displayName) {
        return naturalKeyQuery(tenantId, channel, displayName, false);
    }

    @Override
    public Optional<StoredDestination> findByNaturalKeyForUpdate(
            TenantId tenantId,
            NotificationChannel channel,
            String displayName) {
        return naturalKeyQuery(tenantId, channel, displayName, true);
    }

    @Override
    public Optional<StoredDestination> findByIdForUpdate(TenantId tenantId, UUID destinationId) {
        return jdbcClient.sql(SELECT.formatted("id = :destinationId") + " FOR UPDATE")
                .param("tenantId", tenantId.value())
                .param("destinationId", destinationId)
                .query(this::map)
                .optional();
    }

    @Override
    public void insert(UUID destinationId, NotificationDestinationSpec spec, OffsetDateTime now) {
        var reference = spec.secretReference();
        var affected = jdbcClient.sql("""
                INSERT INTO notification_destinations (
                    id, tenant_id, channel, status, display_name, secret_reference, metadata,
                    created_at, updated_at
                ) VALUES (
                    :destinationId, :tenantId, :channel, :status, :displayName, :secretReference,
                    jsonb_build_object(
                        'referenceFingerprint', :referenceFingerprint,
                        'referenceScheme', :referenceScheme,
                        'readiness', 'ready'
                    ),
                    :now, :now
                )
                """)
                .param("destinationId", destinationId)
                .param("tenantId", spec.tenantId().value())
                .param("channel", spec.channel().databaseValue())
                .param("status", spec.status().databaseValue())
                .param("displayName", spec.displayName())
                .param("secretReference", reference.value())
                .param("referenceFingerprint", reference.fingerprint().value())
                .param("referenceScheme", reference.scheme())
                .param("now", now)
                .update();
        if (affected != 1) {
            throw new NotificationProvisioningException(Code.PERSISTED_STATE_INVALID);
        }
    }

    @Override
    public boolean hasDeliveries(TenantId tenantId, UUID destinationId) {
        return Boolean.TRUE.equals(jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1
                      FROM notification_deliveries
                     WHERE tenant_id = :tenantId
                       AND destination_id = :destinationId
                )
                """)
                .param("tenantId", tenantId.value())
                .param("destinationId", destinationId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public boolean deleteExact(
            TenantId tenantId,
            UUID destinationId,
            NotificationChannel channel,
            String displayName) {
        return jdbcClient.sql("""
                DELETE FROM notification_destinations
                 WHERE tenant_id = :tenantId
                   AND id = :destinationId
                   AND channel = :channel
                   AND display_name = :displayName
                """)
                .param("tenantId", tenantId.value())
                .param("destinationId", destinationId)
                .param("channel", channel.databaseValue())
                .param("displayName", displayName)
                .update() == 1;
    }

    private Optional<StoredDestination> naturalKeyQuery(
            TenantId tenantId,
            NotificationChannel channel,
            String displayName,
            boolean forUpdate) {
        var sql = SELECT.formatted("channel = :channel AND display_name = :displayName");
        if (forUpdate) {
            sql += " FOR UPDATE";
        }
        return jdbcClient.sql(sql)
                .param("tenantId", tenantId.value())
                .param("channel", channel.databaseValue())
                .param("displayName", displayName)
                .query(this::map)
                .optional();
    }

    private StoredDestination map(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            var reference = new OpaqueSecretReference(resultSet.getString("secret_reference"));
            if (!reference.fingerprint().value().equals(resultSet.getString("metadata_reference_fingerprint"))
                    || !reference.scheme().equals(resultSet.getString("metadata_reference_scheme"))
                    || !"ready".equals(resultSet.getString("metadata_readiness"))) {
                throw new NotificationProvisioningException(Code.PERSISTED_STATE_INVALID);
            }
            return new StoredDestination(
                    resultSet.getObject("id", UUID.class),
                    new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                    NotificationChannel.fromDatabaseValue(resultSet.getString("channel")),
                    NotificationDestinationStatus.fromDatabaseValue(resultSet.getString("status")),
                    resultSet.getString("display_name"),
                    reference);
        } catch (IllegalArgumentException exception) {
            throw new NotificationProvisioningException(Code.PERSISTED_STATE_INVALID);
        }
    }
}
