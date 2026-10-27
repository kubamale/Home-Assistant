package dev.kuba.homedisplay.calendar;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;

/**
 * Turns iCalendar text from a CalDAV server into the events that happen on one day. Recurring
 * events are expanded here (rather than trusting the server to), with excluded dates and moved
 * occurrences handled, and cancelled events dropped.
 */
final class ICalExpander {

    private ICalExpander() {
    }

    static List<CalendarEvent> expand(String ics, String calendarName, LocalDate day, ZoneId zone)
            throws IOException, ParserException {
        Calendar calendar = new CalendarBuilder().build(new StringReader(ics));
        Instant windowStart = day.atStartOfDay(zone).toInstant();
        Instant windowEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
        Period<ZonedDateTime> window = new Period<>(day.atStartOfDay(zone), day.plusDays(1).atStartOfDay(zone));

        List<VEvent> masters = new ArrayList<>();
        List<VEvent> overrides = new ArrayList<>();
        Set<String> overriddenOccurrences = new HashSet<>();
        for (VEvent event : calendar.<VEvent>getComponents(Component.VEVENT)) {
            if (isCancelled(event)) {
                // a cancelled occurrence of a recurring event still hides the original occurrence
                recurrenceKey(event, zone).ifPresent(overriddenOccurrences::add);
                continue;
            }
            if (event.getProperty(Property.RECURRENCE_ID).isPresent()) {
                overrides.add(event);
                recurrenceKey(event, zone).ifPresent(overriddenOccurrences::add);
            } else {
                masters.add(event);
            }
        }

        List<CalendarEvent> out = new ArrayList<>();
        for (VEvent master : masters) {
            String uid = master.getUid().map(Property::getValue).orElse("no-uid");
            Set<Period<Temporal>> periods = master.calculateRecurrenceSet(window);
            for (Period<Temporal> period : periods) {
                String key = uid + "|" + instantOf(period.getStart(), zone);
                if (overriddenOccurrences.contains(key)) {
                    continue;
                }
                toEvent(master, uid, period.getStart(), period.getEnd(), calendarName, day, zone, windowStart,
                        windowEnd).ifPresent(out::add);
            }
        }
        for (VEvent override : overrides) {
            Optional<Temporal> start = override.getStartDate().map(p -> (Temporal) p.getDate());
            if (start.isEmpty()) {
                continue;
            }
            Temporal end = override.getEndDate(true).map(p -> (Temporal) p.getDate()).orElse(start.get());
            String uid = override.getUid().map(Property::getValue).orElse("no-uid");
            toEvent(override, uid, start.get(), end, calendarName, day, zone, windowStart, windowEnd)
                    .ifPresent(out::add);
        }
        return out;
    }

    private static Optional<CalendarEvent> toEvent(VEvent event, String uid, Temporal start, Temporal end,
            String calendarName, LocalDate day, ZoneId zone, Instant windowStart, Instant windowEnd) {
        String title = event.getProperty(Property.SUMMARY).map(Property::getValue).filter(s -> !s.isBlank())
                .orElse("(no title)");
        String location = event.getProperty(Property.LOCATION).map(Property::getValue).filter(s -> !s.isBlank())
                .orElse(null);
        Instant startInstant = instantOf(start, zone);
        Instant endInstant = end == null ? startInstant : instantOf(end, zone);

        if (start instanceof LocalDate) {
            // all-day: DTEND is exclusive; the event covers [start, end)
            if (!(startInstant.isBefore(windowEnd) && endInstant.isAfter(windowStart))) {
                return Optional.empty();
            }
            return Optional.of(new CalendarEvent(uid + "|" + start, title, true, null, null, day, location,
                    calendarName));
        }
        boolean overlaps = startInstant.isBefore(windowEnd)
                && (endInstant.isAfter(windowStart) || (endInstant.equals(startInstant) && !startInstant.isBefore(windowStart)));
        if (!overlaps) {
            return Optional.empty();
        }
        return Optional.of(new CalendarEvent(uid + "|" + startInstant, title, false, startInstant, endInstant, null,
                location, calendarName));
    }

    private static boolean isCancelled(VEvent event) {
        return event.getProperty(Property.STATUS).map(p -> "CANCELLED".equalsIgnoreCase(p.getValue())).orElse(false);
    }

    /** uid + the instant of the occurrence an override replaces, as named by its RECURRENCE-ID. */
    private static Optional<String> recurrenceKey(VEvent event, ZoneId zone) {
        Optional<Property> id = event.getProperty(Property.RECURRENCE_ID);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        String uid = event.getUid().map(Property::getValue).orElse("no-uid");
        try {
            Temporal value = ((net.fortuna.ical4j.model.property.RecurrenceId<?>) id.get()).getDate();
            return Optional.of(uid + "|" + instantOf(value, zone));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Floating times (no zone) mean "wall-clock time wherever you are", so use the display zone. */
    static Instant instantOf(Temporal t, ZoneId zone) {
        if (t instanceof ZonedDateTime z) {
            return z.toInstant();
        }
        if (t instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        if (t instanceof Instant i) {
            return i;
        }
        if (t instanceof LocalDateTime l) {
            return l.atZone(zone).toInstant();
        }
        if (t instanceof LocalDate d) {
            return d.atStartOfDay(zone).toInstant();
        }
        throw new IllegalArgumentException("Unsupported time type " + t.getClass());
    }
}
