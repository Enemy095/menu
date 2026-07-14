package com.salamu.menu.site.api;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record WeeklyScheduleDay(DayOfWeek dayOfWeek, List<OpeningInterval> intervals) {

    public WeeklyScheduleDay {
        Objects.requireNonNull(dayOfWeek, "dayOfWeek");
        var sorted = new ArrayList<>(SiteValues.list(intervals, "intervals"));
        if (sorted.size() > 8) {
            throw new IllegalArgumentException("A day cannot contain more than eight intervals");
        }
        sorted.sort(null);
        for (int index = 1; index < sorted.size(); index++) {
            if (!sorted.get(index - 1).closesAt().isBefore(sorted.get(index).opensAt())) {
                throw new IllegalArgumentException("Opening intervals overlap or duplicate");
            }
        }
        intervals = List.copyOf(sorted);
    }
}
