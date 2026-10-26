package com.salamu.menu.notification.internal.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.api.NotificationDestinationStatus;
import com.salamu.menu.notification.internal.application.NotificationDestinationQueryService;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcNotificationDestinationReadRepositoryPostgreSqlTests {

    private PostgreSQLContainer postgres;
    private JdbcTemplate jdbc;
    private NotificationDestinationQueryService query;

    @BeforeAll
    void startPostgreSqlAndMigrateToFinalSchema() {
        postgres = new PostgreSQLContainer("postgres:17.5-alpine3.21")
                .withDatabaseName("notification_readiness")
                .withUsername("notification_readiness")
                .withPassword("notification_readiness");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        query = new NotificationDestinationQueryService(
                new JdbcNotificationDestinationReadRepository(JdbcClient.create(dataSource)));
    }

    @AfterAll
    void stopPostgreSql() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void resetDestinations() {
        jdbc.update("DELETE FROM notification_destinations");
    }

    @Test
    void finalTableReadIsOrderedSanitizedAndFailsMalformedReferenceReadiness() {
        insert(3, "telegram", "active", "Zulu", "secret://application/telegram/zulu");
        insert(1, "email", "active", "Alpha", "not-a-secret-manager-reference");
        insert(2, "pos", "disabled", "Beta", "vault://application/pos/beta");

        var result = query.configured();

        assertThat(result).extracting(value -> value.channel())
                .containsExactly(NotificationChannel.EMAIL, NotificationChannel.POS, NotificationChannel.TELEGRAM);
        assertThat(result).extracting(value -> value.status())
                .containsExactly(
                        NotificationDestinationStatus.ACTIVE,
                        NotificationDestinationStatus.DISABLED,
                        NotificationDestinationStatus.ACTIVE);
        assertThat(result).extracting(value -> value.ready()).containsExactly(false, false, true);
        assertThat(result.toString())
                .doesNotContain("not-a-secret-manager-reference", "secret://", "vault://");
    }

    private void insert(long suffix, String channel, String status, String name, String reference) {
        jdbc.update("""
                INSERT INTO notification_destinations (
                    id, channel, status, display_name, secret_reference, metadata
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                """,
                new UUID(0, suffix), channel, status, name, reference, "{}");
    }
}
