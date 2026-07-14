package com.salamu.menu.site.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class SiteConfigurationValueTests {

    @Test
    void orderLimitsEnforceEveryAcceptedMinimumAndMaximum() {
        assertThat(new OrderLimits(1, 1, 1, 5, 1, 1, 0)).isNotNull();
        assertThat(new OrderLimits(99, 200, 80, 32, 240, 500, 1440)).isNotNull();

        List<Runnable> invalid = List.of(
                () -> new OrderLimits(0, 1, 1, 5, 1, 1, 0),
                () -> new OrderLimits(100, 1, 1, 5, 1, 1, 0),
                () -> new OrderLimits(1, 0, 1, 5, 1, 1, 0),
                () -> new OrderLimits(1, 201, 1, 5, 1, 1, 0),
                () -> new OrderLimits(1, 1, 0, 5, 1, 1, 0),
                () -> new OrderLimits(1, 1, 81, 5, 1, 1, 0),
                () -> new OrderLimits(1, 1, 1, 4, 1, 1, 0),
                () -> new OrderLimits(1, 1, 1, 33, 1, 1, 0),
                () -> new OrderLimits(1, 1, 1, 5, 0, 1, 0),
                () -> new OrderLimits(1, 1, 1, 5, 241, 1, 0),
                () -> new OrderLimits(1, 1, 1, 5, 1, 0, 0),
                () -> new OrderLimits(1, 1, 1, 5, 1, 501, 0),
                () -> new OrderLimits(1, 1, 1, 5, 1, 1, -1),
                () -> new OrderLimits(1, 1, 1, 5, 1, 1, 1441));

        invalid.forEach(value -> assertThatThrownBy(value::run).isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void weeklyScheduleIsExactlyMondayThroughSundayAndIntervalsAreSorted() {
        var later = new OpeningInterval(LocalTime.of(13, 0), LocalTime.of(14, 0));
        var earlier = new OpeningInterval(LocalTime.of(10, 0), LocalTime.of(12, 0));
        var schedule = new WeeklySchedule(
                "Europe/Moscow",
                "Every day",
                Arrays.stream(DayOfWeek.values())
                        .map(day -> new WeeklyScheduleDay(
                                day,
                                day == DayOfWeek.MONDAY ? List.of(later, earlier) : List.of()))
                        .toList());

        assertThat(schedule.days()).extracting(WeeklyScheduleDay::dayOfWeek)
                .containsExactly(DayOfWeek.values());
        assertThat(schedule.days().getFirst().intervals()).containsExactly(earlier, later);

        var wrongOrder = Arrays.stream(DayOfWeek.values())
                .map(day -> new WeeklyScheduleDay(day, List.of()))
                .toList()
                .reversed();
        assertThatThrownBy(() -> new WeeklySchedule("Europe/Moscow", "Every day", wrongOrder))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WeeklyScheduleDay(
                        DayOfWeek.MONDAY,
                        List.of(
                                new OpeningInterval(LocalTime.of(10, 0), LocalTime.of(12, 0)),
                                new OpeningInterval(LocalTime.of(11, 0), LocalTime.of(13, 0)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiredLocalizedStringsMayBeEmptyOnlyWhereTheOpenApiAllowsIt() {
        assertThat(new LocalizedSiteText(
                        "ru-RU", "Name", "Name", "Restaurant", "", "", "", "Menu", "Cart", "Order"))
                .isNotNull();
        assertThatThrownBy(() -> new LocalizedSiteText(
                        "ru-RU", "Name", "Name", "Restaurant", null, "", "", "Menu", "Cart", "Order"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void deliveryContractAlwaysRequiresAnAddressAndUsesTheTypedFeePolicy() {
        assertThat(new DeliveryConfiguration(false, true, DeliveryFeePolicy.NONE).addressRequired()).isTrue();
        assertThatThrownBy(() -> new DeliveryConfiguration(true, false, DeliveryFeePolicy.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
