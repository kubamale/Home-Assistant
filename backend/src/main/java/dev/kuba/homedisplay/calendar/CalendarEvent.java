package dev.kuba.homedisplay.calendar;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One calendar event on the day being shown. Timed events carry {@code start}/{@code end};
 * all-day events carry only {@code date}.
 */
public record CalendarEvent(
        String id,
        String title,
        boolean allDay,
        Instant start,
        Instant end,
        LocalDate date,
        String location,
        String calendar) {
}
