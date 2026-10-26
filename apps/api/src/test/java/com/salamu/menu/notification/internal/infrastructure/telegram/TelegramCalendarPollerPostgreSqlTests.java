package com.salamu.menu.notification.internal.infrastructure.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

class TelegramCalendarPollerPostgreSqlTests {
    @Test void laterCommandFailureDoesNotReplayAnAlreadyAnsweredCommand() {
        try (var postgres = new PostgreSQLContainer("postgres:17.5-alpine3.21")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).load().migrate();
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO telegram_calendar_poll_state VALUES (1, 0)");
            var bot = mock(TelegramCalendarGateway.class);
            var handler = mock(TelegramCalendarUpdateHandler.class);
            var mapper = new ObjectMapper();
            var first = mapper.readTree("{\"update_id\":1}");
            var second = mapper.readTree("{\"update_id\":2}");
            when(bot.updates(1)).thenReturn(mapper.readTree("[{\"update_id\":1},{\"update_id\":2}]"));
            when(bot.updates(2)).thenReturn(mapper.readTree("[{\"update_id\":2}]"));
            doThrow(new IllegalStateException("simulated failure")).when(handler).handle(second);
            var poller = new TelegramCalendarPoller(jdbc, new DataSourceTransactionManager(source), bot, handler);
            poller.poll(); poller.poll(); poller.poll();
            verify(handler, times(1)).handle(first);
            verify(handler, times(2)).handle(second);
            assertThat(jdbc.queryForObject("SELECT last_update_id FROM telegram_calendar_poll_state", Long.class)).isEqualTo(1L);
        }
    }
}
