package dev.kuba.homedisplay.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

class ICalExpanderTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final LocalDate DAY = LocalDate.parse("2026-10-24"); // a Saturday, UTC+2

    private static String ics(String... events) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\n" + String.join("", events)
                + "END:VCALENDAR\r\n";
    }

    private static String event(String... lines) {
        return "BEGIN:VEVENT\r\n" + String.join("\r\n", lines) + "\r\nEND:VEVENT\r\n";
    }

    private static List<CalendarEvent> expand(String text, LocalDate day) throws Exception {
        return ICalExpander.expand(text, "Home", day, WARSAW).stream()
                .sorted(Comparator.comparing(e -> e.start() == null ? Instant.MIN : e.start())).toList();
    }

    @Test
    void timedEventWithATimezoneId() throws Exception {
        var events = expand(ics(event("UID:a1", "DTSTAMP:20261001T000000Z",
                "DTSTART;TZID=Europe/Warsaw:20261024T113000", "DTEND;TZID=Europe/Warsaw:20261024T123000",
                "SUMMARY:Dentist", "LOCATION:Floriańska 12")), DAY);

        assertEquals(1, events.size());
        CalendarEvent e = events.get(0);
        assertEquals("Dentist", e.title());
        assertEquals("Floriańska 12", e.location());
        assertEquals("Home", e.calendar());
        assertFalse(e.allDay());
        assertEquals(Instant.parse("2026-10-24T09:30:00Z"), e.start());
        assertEquals(Instant.parse("2026-10-24T10:30:00Z"), e.end());
    }

    @Test
    void utcAndFloatingTimes() throws Exception {
        var events = expand(ics(
                event("UID:u", "DTSTAMP:20261001T000000Z", "DTSTART:20261024T080000Z", "DTEND:20261024T090000Z",
                        "SUMMARY:UTC"),
                event("UID:f", "DTSTAMP:20261001T000000Z", "DTSTART:20261024T150000", "DTEND:20261024T160000",
                        "SUMMARY:Floating")), DAY);

        assertEquals(2, events.size());
        assertEquals(Instant.parse("2026-10-24T08:00:00Z"), events.get(0).start());
        // floating 15:00 is read as 15:00 in the display timezone (UTC+2)
        assertEquals(Instant.parse("2026-10-24T13:00:00Z"), events.get(1).start());
    }

    @Test
    void allDayEventsHaveAnExclusiveEndAndSpanDays() throws Exception {
        String conference = event("UID:c", "DTSTAMP:20261001T000000Z", "DTSTART;VALUE=DATE:20261023",
                "DTEND;VALUE=DATE:20261026", "SUMMARY:Conference");
        String single = event("UID:s", "DTSTAMP:20261001T000000Z", "DTSTART;VALUE=DATE:20261024",
                "DTEND;VALUE=DATE:20261025", "SUMMARY:Trash pickup");
        String yesterday = event("UID:y", "DTSTAMP:20261001T000000Z", "DTSTART;VALUE=DATE:20261023",
                "DTEND;VALUE=DATE:20261024", "SUMMARY:Yesterday");

        var events = expand(ics(conference, single, yesterday), DAY);
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(CalendarEvent::allDay));
        assertTrue(events.stream().allMatch(e -> DAY.equals(e.date())));
        assertNull(events.get(0).start());
        assertEquals(List.of("Conference", "Trash pickup"),
                events.stream().map(CalendarEvent::title).sorted().toList());
    }

    @Test
    void eventsOutsideTheDayAreLeftOut() throws Exception {
        String lateNight = event("UID:l", "DTSTAMP:20261001T000000Z", "DTSTART;TZID=Europe/Warsaw:20261023T220000",
                "DTEND;TZID=Europe/Warsaw:20261023T233000", "SUMMARY:Yesterday evening");
        String tomorrow = event("UID:t", "DTSTAMP:20261001T000000Z", "DTSTART;TZID=Europe/Warsaw:20261025T090000",
                "DTEND;TZID=Europe/Warsaw:20261025T100000", "SUMMARY:Tomorrow");
        assertEquals(0, expand(ics(lateNight, tomorrow), DAY).size());
    }

    @Test
    void eventsRunningPastMidnightAppearOnBothDays() throws Exception {
        String party = event("UID:p", "DTSTAMP:20261001T000000Z", "DTSTART;TZID=Europe/Warsaw:20261023T230000",
                "DTEND;TZID=Europe/Warsaw:20261024T020000", "SUMMARY:Party");
        assertEquals(1, expand(ics(party), DAY).size());
        assertEquals(1, expand(ics(party), DAY.minusDays(1)).size());
        assertEquals(0, expand(ics(party), DAY.plusDays(1)).size());
    }

    @Test
    void recurringEventsAreExpandedWithExcludedDatesAndMovedOccurrences() throws Exception {
        // Every Saturday at 10:00; the 17th is skipped, and the 24th has been moved to 14:00
        String series = event("UID:weekly", "DTSTAMP:20261001T000000Z", "DTSTART;TZID=Europe/Warsaw:20261003T100000",
                "DTEND;TZID=Europe/Warsaw:20261003T110000", "RRULE:FREQ=WEEKLY;BYDAY=SA",
                "EXDATE;TZID=Europe/Warsaw:20261017T100000", "SUMMARY:Swim class");
        String moved = event("UID:weekly", "DTSTAMP:20261001T000000Z", "RECURRENCE-ID;TZID=Europe/Warsaw:20261024T100000",
                "DTSTART;TZID=Europe/Warsaw:20261024T140000", "DTEND;TZID=Europe/Warsaw:20261024T150000",
                "SUMMARY:Swim class (moved)");

        var oct24 = expand(ics(series, moved), DAY);
        assertEquals(1, oct24.size(), "the original 10:00 occurrence must be replaced, not shown twice");
        assertEquals("Swim class (moved)", oct24.get(0).title());
        assertEquals(Instant.parse("2026-10-24T12:00:00Z"), oct24.get(0).start());

        assertEquals(0, expand(ics(series, moved), LocalDate.parse("2026-10-17")).size(), "excluded date");
        var oct10 = expand(ics(series, moved), LocalDate.parse("2026-10-10"));
        assertEquals(1, oct10.size());
        assertEquals("Swim class", oct10.get(0).title());
        assertEquals(0, expand(ics(series, moved), LocalDate.parse("2026-10-25")).size(), "not a Saturday");
    }

    @Test
    void cancelledEventsAreDropped() throws Exception {
        String cancelled = event("UID:x", "DTSTAMP:20261001T000000Z", "DTSTART;TZID=Europe/Warsaw:20261024T100000",
                "DTEND;TZID=Europe/Warsaw:20261024T110000", "STATUS:CANCELLED", "SUMMARY:Cancelled meeting");
        assertEquals(0, expand(ics(cancelled), DAY).size());
    }

    @Test
    void recurringEventWithOneCancelledOccurrence() throws Exception {
        String series = event("UID:daily", "DTSTAMP:20261001T000000Z", "DTSTART;TZID=Europe/Warsaw:20261020T080000",
                "DTEND;TZID=Europe/Warsaw:20261020T083000", "RRULE:FREQ=DAILY;COUNT=10", "SUMMARY:Stand-up");
        String cancelledOne = event("UID:daily", "DTSTAMP:20261001T000000Z",
                "RECURRENCE-ID;TZID=Europe/Warsaw:20261024T080000", "DTSTART;TZID=Europe/Warsaw:20261024T080000",
                "DTEND;TZID=Europe/Warsaw:20261024T083000", "STATUS:CANCELLED", "SUMMARY:Stand-up");
        assertEquals(0, expand(ics(series, cancelledOne), DAY).size());
        assertEquals(1, expand(ics(series, cancelledOne), DAY.plusDays(1)).size());
    }

    @Test
    void eventsWithoutATitleStillShow() throws Exception {
        var events = expand(ics(event("UID:n", "DTSTAMP:20261001T000000Z", "DTSTART:20261024T080000Z",
                "DTEND:20261024T090000Z")), DAY);
        assertEquals("(no title)", events.get(0).title());
    }
}
