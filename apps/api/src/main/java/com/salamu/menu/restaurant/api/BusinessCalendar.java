package com.salamu.menu.restaurant.api;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public interface BusinessCalendar {
    CalendarSnapshot current(ZoneId timezone);
    void setDateClosed(LocalDate date, boolean closed, ZoneId timezone);
    void setWeekdayClosed(DayOfWeek day, boolean closed);

    record CalendarSnapshot(LocalDate businessDate, List<Integer> weeklyClosedDays, List<LocalDate> closedDates) {
        public CalendarSnapshot {
            weeklyClosedDays = List.copyOf(weeklyClosedDays);
            closedDates = List.copyOf(closedDates);
        }
        public boolean closedToday() {
            return weeklyClosedDays.contains(businessDate.getDayOfWeek().getValue()) || closedDates.contains(businessDate);
        }
    }
}
