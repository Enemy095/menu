package com.salamu.menu.notification.internal.infrastructure.telegram;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import com.salamu.menu.restaurant.api.BusinessCalendar;
import com.salamu.menu.restaurant.api.BusinessCalendar.CalendarSnapshot;
import com.salamu.menu.site.api.SiteConfigurationQuery;
import com.salamu.menu.site.api.SiteConfigurationSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TelegramCalendarTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TelegramCalendarGateway bot = mock(TelegramCalendarGateway.class);
    private final BusinessCalendar calendar = mock(BusinessCalendar.class);
    private final SiteConfigurationQuery site = mock(SiteConfigurationQuery.class);
    private final TelegramCalendarUpdateHandler handler = new TelegramCalendarUpdateHandler(
            new TelegramCalendarProperties(true,Set.of(123L)),bot,calendar,site);

    private void state() {
        var configuration=mock(SiteConfigurationSnapshot.class);
        when(site.current()).thenReturn(configuration);
        when(configuration.timezone()).thenReturn("Europe/Moscow");
        when(calendar.current(ZoneId.of("Europe/Moscow"))).thenReturn(new CalendarSnapshot(LocalDate.of(2026,10,26),List.of(),List.of()));
    }
    @Test void rejectsUnknownUsersAndGroupCallbacksWithoutAccessingBusinessData() {
        handler.handle(mapper.readTree("{\"message\":{\"from\":{\"id\":99},\"chat\":{\"id\":99,\"type\":\"private\"},\"text\":\"Добавить выходной\"}}"));
        handler.handle(mapper.readTree("{\"callback_query\":{\"id\":\"x\",\"from\":{\"id\":123},\"message\":{\"chat\":{\"id\":-10,\"type\":\"group\"}},\"data\":\"week:3:1\"}}"));
        verifyNoInteractions(calendar,site,bot);
    }
    @Test void ownerGetsMenuCalendarAndIdempotentDesiredStateCallbacks() {
        state();
        handler.handle(mapper.readTree("{\"message\":{\"from\":{\"id\":123},\"chat\":{\"id\":123,\"type\":\"private\"},\"text\":\"/start\"}}"));
        verify(bot).send(eq(123L),contains("Управление выходными"),anyMap());
        var update=mapper.readTree("{\"callback_query\":{\"id\":\"x\",\"from\":{\"id\":123},\"message\":{\"message_id\":5,\"chat\":{\"id\":123,\"type\":\"private\"}},\"data\":\"week:3:1\"}}" );
        handler.handle(update); handler.handle(update);
        verify(calendar,times(2)).setWeekdayClosed(DayOfWeek.WEDNESDAY,true);
        verify(calendar,never()).setWeekdayClosed(DayOfWeek.WEDNESDAY,false);
    }
    @Test void fullYearSummaryFitsTelegramAndMalformedDatesDoNotBlockPolling() {
        state();
        var today = LocalDate.of(2026, 10, 26);
        when(calendar.current(ZoneId.of("Europe/Moscow"))).thenReturn(new CalendarSnapshot(today,
                List.of(1, 2, 3, 4, 5, 6, 7), today.datesUntil(today.plusDays(366)).toList()));
        handler.handle(mapper.readTree("{\"message\":{\"from\":{\"id\":123},\"chat\":{\"id\":123,\"type\":\"private\"},\"text\":\"/days\"}}"));
        var message = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(bot).send(eq(123L), message.capture(), anyMap());
        assertThat(message.getValue()).contains("26.10.26").hasSizeLessThanOrEqualTo(4096);
        handler.handle(mapper.readTree("{\"callback_query\":{\"id\":\"bad\",\"from\":{\"id\":123},\"message\":{\"message_id\":5,\"chat\":{\"id\":123,\"type\":\"private\"}},\"data\":\"day:invalid:1\"}}"));
        verify(bot).answer("bad");
        verify(calendar, never()).setDateClosed(any(), anyBoolean(), any());
    }
    @Test void leapMonthAlignsToMondayAndPastDatesHaveNoAction() {
        var state=new CalendarSnapshot(LocalDate.of(2028,2,10),List.of(),List.of(LocalDate.of(2028,2,29)));
        String keyboard=mapper.writeValueAsString(TelegramCalendarKeyboard.month(YearMonth.of(2028,2),state));
        assertThat(keyboard).contains("day:2028-02-29:0").doesNotContain("day:2028-02-09").doesNotContain("2028-02-30");
        assertThatThrownBy(()->new TelegramCalendarProperties(true,Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(new TelegramCalendarProperties(true,Set.of(123L)).toString()).doesNotContain("123");
    }
}
