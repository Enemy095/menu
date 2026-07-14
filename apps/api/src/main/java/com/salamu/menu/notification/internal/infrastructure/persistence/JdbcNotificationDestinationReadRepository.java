package com.salamu.menu.notification.internal.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.api.NotificationDestinationId;
import com.salamu.menu.notification.api.NotificationDestinationStatus;
import com.salamu.menu.notification.internal.application.NotificationDestinationReadRepository;
import com.salamu.menu.notification.internal.application.OpaqueSecretReference;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcNotificationDestinationReadRepository implements NotificationDestinationReadRepository {

    private static final String SELECT_ALL = """
            SELECT id, channel, status, display_name, secret_reference
              FROM notification_destinations
             ORDER BY channel, display_name, id
            """;

    private final JdbcClient jdbcClient;

    JdbcNotificationDestinationReadRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<StoredDestination> findAllOrdered() {
        return jdbcClient.sql(SELECT_ALL)
                .query((resultSet, rowNumber) -> new StoredDestination(
                        new NotificationDestinationId(resultSet.getObject("id", UUID.class)),
                        NotificationChannel.fromDatabaseValue(resultSet.getString("channel")),
                        NotificationDestinationStatus.fromDatabaseValue(resultSet.getString("status")),
                        resultSet.getString("display_name"),
                        new OpaqueSecretReference(resultSet.getString("secret_reference"))))
                .list();
    }
}
