package com.salamu.menu.notification.internal.infrastructure.telegram;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.salamu.menu.restaurant.api.BusinessCalendar.CalendarSnapshot;

final class TelegramCalendarKeyboard {
    static final List<String> DAYS = List.of("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье");
    private TelegramCalendarKeyboard() {}
    static Map<String, Object> button(String text, String data) { return Map.of("text", text, "callback_data", data); }
    static Map<String, Object> menu() {
        return Map.of("keyboard", List.of(List.of(Map.of("text", "Добавить выходной")),
                List.of(Map.of("text", "Дни недели"), Map.of("text", "Предстоящие выходные"))), "resize_keyboard", true);
    }
    static Map<String, Object> month(YearMonth month, CalendarSnapshot state) {
        var rows = new ArrayList<List<Map<String, Object>>>();
        rows.add(List.of(button(month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"))), "noop")));
        rows.add(List.of("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").stream().map(day -> button(day, "noop")).toList());
        var row = new ArrayList<Map<String, Object>>();
        for (int blank = 1; blank < month.atDay(1).getDayOfWeek().getValue(); blank++) row.add(button("·", "noop"));
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            boolean closed = state.closedDates().contains(date);
            boolean valid = !date.isBefore(state.businessDate()) && !date.isAfter(state.businessDate().plusDays(365));
            row.add(button((closed ? "✕ " : "") + day, valid ? "day:" + date + ":" + (closed ? "0" : "1") : "noop"));
            if (row.size() == 7) { rows.add(List.copyOf(row)); row.clear(); }
        }
        if (!row.isEmpty()) { while (row.size() < 7) row.add(button("·", "noop")); rows.add(List.copyOf(row)); }
        var first = YearMonth.from(state.businessDate());
        var last = YearMonth.from(state.businessDate().plusDays(365));
        rows.add(List.of(button("←", month.isAfter(first) ? "cal:" + month.minusMonths(1) : "noop"),
                button("→", month.isBefore(last) ? "cal:" + month.plusMonths(1) : "noop")));
        return Map.of("inline_keyboard", rows);
    }
    static Map<String, Object> weekdays(CalendarSnapshot state) {
        var rows = new ArrayList<List<Map<String,Object>>>();
        for (int day = 1; day <= 7; day++) {
            boolean closed = state.weeklyClosedDays().contains(day);
            rows.add(List.of(button((closed ? "✕ " : "") + DAYS.get(day - 1), "week:" + day + ":" + (closed ? "0" : "1"))));
        }
        return Map.of("inline_keyboard", rows);
    }
}
