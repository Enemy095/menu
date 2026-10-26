package com.salamu.menu.restaurant.internal.application;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class BusinessCalendarPostgreSqlTests {
    @Test
    void persistsIdempotentDatesAndWeekdaysInBusinessTimezoneAndRejectsOutOfRangeDates() {
        try (var postgres = new PostgreSQLContainer("postgres:17.5-alpine3.21")) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
            var clock = Clock.fixed(Instant.parse("2026-10-27T22:30:00Z"), ZoneOffset.UTC);
            var service = new BusinessCalendarService(jdbc, clock);
            var zone = ZoneId.of("Europe/Moscow");
            var today = LocalDate.of(2026,10,28);
            assertThat(service.current(zone).businessDate()).isEqualTo(today);
            assertThat(service.current(zone).closedToday()).isFalse();
            service.setDateClosed(today, true, zone);
            service.setDateClosed(today, true, zone);
            assertThat(service.current(zone).closedDates()).containsExactly(today);
            assertThat(service.current(zone).closedToday()).isTrue();
            service.setDateClosed(today, false, zone);
            service.setWeekdayClosed(DayOfWeek.WEDNESDAY, true);
            service.setWeekdayClosed(DayOfWeek.WEDNESDAY, true);
            assertThat(service.current(zone).weeklyClosedDays()).containsExactly(3);
            assertThat(service.current(zone).closedToday()).isTrue();
            service.setWeekdayClosed(DayOfWeek.WEDNESDAY, false);
            assertThat(service.current(zone).closedToday()).isFalse();
            assertThatThrownBy(() -> service.setDateClosed(today.minusDays(1),true,zone)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.setDateClosed(today.plusDays(366),true,zone)).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM restaurant_closed_dates",Integer.class)).isZero();
        }
    }
}
