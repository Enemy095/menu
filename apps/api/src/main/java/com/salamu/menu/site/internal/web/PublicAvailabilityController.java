package com.salamu.menu.site.internal.web;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.salamu.menu.restaurant.api.BusinessCalendar;
import com.salamu.menu.site.api.SiteConfigurationQuery;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicAvailabilityController {
    private final BusinessCalendar calendar;
    private final SiteConfigurationQuery site;
    public PublicAvailabilityController(BusinessCalendar calendar, SiteConfigurationQuery site) {
        this.calendar = calendar; this.site = site;
    }
    @GetMapping("/api/v1/public/availability")
    public ResponseEntity<Availability> current() {
        var configuration = site.current();
        var snapshot = calendar.current(ZoneId.of(configuration.timezone()));
        var days = new java.util.TreeSet<>(snapshot.weeklyClosedDays());
        configuration.schedule().days().stream().filter(day -> day.intervals().isEmpty())
                .forEach(day -> days.add(day.dayOfWeek().getValue()));
        boolean closed = snapshot.closedDates().contains(snapshot.businessDate())
                || days.contains(snapshot.businessDate().getDayOfWeek().getValue());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Availability(snapshot.businessDate(), configuration.timezone(), closed,
                        List.copyOf(days), snapshot.closedDates(), snapshot.nextOpenDate(days)));
    }
    public record Availability(LocalDate businessDate, String timezone, boolean closedToday,
            List<Integer> weeklyClosedDays, List<LocalDate> closedDates, LocalDate nextOpenDate) {}
}
