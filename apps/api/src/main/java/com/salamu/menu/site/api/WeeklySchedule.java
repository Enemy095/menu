package com.salamu.menu.site.api;

import java.time.DayOfWeek;
import java.util.List;

public record WeeklySchedule(String timezone, String displayText, List<WeeklyScheduleDay> days) {

    public WeeklySchedule {
        timezone = SiteValues.timezone(timezone);
        displayText = SiteValues.required(displayText, 1, 240, "displayText");
        days = SiteValues.list(days, "days");
        if (days.size() != DayOfWeek.values().length) {
            throw new IllegalArgumentException("Schedule must contain exactly seven days");
        }
        for (int index = 0; index < days.size(); index++) {
            if (days.get(index).dayOfWeek() != DayOfWeek.of(index + 1)) {
                throw new IllegalArgumentException("Schedule days must be ordered Monday through Sunday");
            }
        }
    }
}
