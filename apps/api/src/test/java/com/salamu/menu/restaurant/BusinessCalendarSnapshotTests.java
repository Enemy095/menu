package com.salamu.menu.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import com.salamu.menu.restaurant.api.BusinessCalendar.CalendarSnapshot;
import org.junit.jupiter.api.Test;

class BusinessCalendarSnapshotTests {
    @Test void skipsConsecutiveExplicitAndRecurringClosures() {
        var state = new CalendarSnapshot(LocalDate.of(2026,10,28), List.of(3), List.of(LocalDate.of(2026,10,29)));
        assertThat(state.nextOpenDate(Set.of(3))).isEqualTo(LocalDate.of(2026,10,30));
    }
    @Test void doesNotInventAReopeningDateWhenEveryWeekdayIsClosed() {
        var state = new CalendarSnapshot(LocalDate.of(2026,10,28), List.of(1,2,3,4,5,6,7), List.of());
        assertThat(state.nextOpenDate(Set.of(1,2,3,4,5,6,7))).isNull();
    }
}
