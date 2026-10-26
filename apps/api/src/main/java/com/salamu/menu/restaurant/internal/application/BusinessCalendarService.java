package com.salamu.menu.restaurant.internal.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;

import com.salamu.menu.restaurant.api.BusinessCalendar;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessCalendarService implements BusinessCalendar {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public BusinessCalendarService(JdbcTemplate jdbc) { this(jdbc, Clock.systemUTC()); }
    BusinessCalendarService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Override
    @Transactional(readOnly = true)
    public CalendarSnapshot current(ZoneId timezone) {
        var today = LocalDate.now(clock.withZone(timezone));
        var days = jdbc.query("SELECT iso_day FROM restaurant_weekly_closed_days ORDER BY iso_day",
                (rs, row) -> rs.getInt(1));
        var dates = jdbc.query("SELECT closed_date FROM restaurant_closed_dates WHERE closed_date BETWEEN ? AND ? ORDER BY closed_date",
                (rs, row) -> rs.getObject(1, LocalDate.class), today, today.plusDays(365));
        return new CalendarSnapshot(today, days, dates);
    }

    @Override
    @Transactional
    public void setDateClosed(LocalDate date, boolean closed, ZoneId timezone) {
        var today = LocalDate.now(clock.withZone(timezone));
        if (date == null || date.isBefore(today) || date.isAfter(today.plusDays(365))) {
            throw new IllegalArgumentException("Date must be within the next 365 days");
        }
        if (closed) jdbc.update("INSERT INTO restaurant_closed_dates(closed_date) VALUES (?) ON CONFLICT DO NOTHING", date);
        else jdbc.update("DELETE FROM restaurant_closed_dates WHERE closed_date = ?", date);
    }

    @Override
    @Transactional
    public void setWeekdayClosed(DayOfWeek day, boolean closed) {
        if (day == null) throw new IllegalArgumentException("Weekday is required");
        if (closed) jdbc.update("INSERT INTO restaurant_weekly_closed_days(iso_day) VALUES (?) ON CONFLICT DO NOTHING", day.getValue());
        else jdbc.update("DELETE FROM restaurant_weekly_closed_days WHERE iso_day = ?", day.getValue());
    }
}
