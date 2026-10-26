package com.salamu.menu.notification.internal.infrastructure.telegram;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import com.salamu.menu.restaurant.api.BusinessCalendar;
import com.salamu.menu.site.api.SiteConfigurationQuery;
import tools.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.telegram-calendar", name = "enabled", havingValue = "true")
final class TelegramCalendarUpdateHandler {
    private final TelegramCalendarProperties owners;
    private final TelegramCalendarGateway bot;
    private final BusinessCalendar calendar;
    private final SiteConfigurationQuery site;
    TelegramCalendarUpdateHandler(TelegramCalendarProperties owners, TelegramCalendarGateway bot, BusinessCalendar calendar, SiteConfigurationQuery site) {
        this.owners = owners; this.bot = bot; this.calendar = calendar; this.site = site;
    }
    void handle(JsonNode update) {
        boolean callback = update.has("callback_query");
        var event = callback ? update.path("callback_query") : update.path("message");
        var message = callback ? event.path("message") : event;
        long user = event.path("from").path("id").asLong(0);
        long chat = message.path("chat").path("id").asLong(0);
        // Telegram user ID is the authorization boundary, never a group/chat destination.
        if (!owners.ownerUserIds().contains(user) || chat != user
                || !message.path("chat").path("type").asText("").equals("private")) return;
        ZoneId zone = ZoneId.of(site.current().timezone());
        var state = calendar.current(zone);
        if (!callback) {
            String command = event.path("text").asText("");
            if (command.equals("Добавить выходной") || command.startsWith("/calendar")) {
                bot.send(chat, "Выберите даты выходных. ✕ — выходной, повторное нажатие отменяет его.", TelegramCalendarKeyboard.month(YearMonth.from(state.businessDate()), state));
            } else if (command.equals("Дни недели")) {
                bot.send(chat, "Повторяющиеся выходные: выберите дни недели.", TelegramCalendarKeyboard.weekdays(state));
            } else if (command.equals("Предстоящие выходные") || command.startsWith("/days")) {
                String days = state.weeklyClosedDays().stream().map(day -> TelegramCalendarKeyboard.DAYS.get(day - 1)).collect(java.util.stream.Collectors.joining(", "));
                // A full year's selection must fit Telegram's 4096-character message limit.
                var dateFormat = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yy", java.util.Locale.ROOT);
                String dates = state.closedDates().stream().map(dateFormat::format).collect(java.util.stream.Collectors.joining(", "));
                bot.send(chat, "Еженедельно: " + (days.isBlank() ? "нет" : days) + "\nДаты: " + (dates.isBlank() ? "нет" : dates), TelegramCalendarKeyboard.menu());
            } else {
                bot.send(chat, "Управление выходными «Вам пицца». Выберите действие.", TelegramCalendarKeyboard.menu());
            }
            return;
        }
        String data = event.path("data").asText("");
        long messageId = message.path("message_id").asLong(0);
        try {
            String[] parts = data.split(":");
            if (parts.length == 2 && parts[0].equals("cal")) {
                var month = YearMonth.parse(parts[1]);
                if (!month.isBefore(YearMonth.from(state.businessDate())) && !month.isAfter(YearMonth.from(state.businessDate().plusDays(365))))
                    bot.edit(chat, messageId, "Выберите даты выходных. ✕ — выходной.", TelegramCalendarKeyboard.month(month, state));
            } else if (parts.length == 3 && parts[0].equals("day") && (parts[2].equals("0") || parts[2].equals("1"))) {
                var date = LocalDate.parse(parts[1]);
                calendar.setDateClosed(date, parts[2].equals("1"), zone);
                bot.edit(chat, messageId, "Выберите даты выходных. ✕ — выходной.", TelegramCalendarKeyboard.month(YearMonth.from(date), calendar.current(zone)));
            } else if (parts.length == 3 && parts[0].equals("week") && (parts[2].equals("0") || parts[2].equals("1"))) {
                calendar.setWeekdayClosed(DayOfWeek.of(Integer.parseInt(parts[1])), parts[2].equals("1"));
                bot.edit(chat, messageId, "Повторяющиеся выходные: выберите дни недели.", TelegramCalendarKeyboard.weekdays(calendar.current(zone)));
            }
        } catch (IllegalArgumentException | java.time.DateTimeException ignored) { /* Stale/out-of-range callback: no change. */ }
        bot.answer(event.path("id").asText(""));
    }
}
