package dev.kuba.homedisplay.calendar;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Where events come from (iCloud CalDAV in production, a fake for development). */
public interface CalendarSource {

    /** False when no calendar is set up, so the UI can say so instead of showing an empty day. */
    boolean configured();

    /** Whether everyone gets this source, not just the admin. Only the sample calendar, so every login has a demo. */
    default boolean sharedWithEveryone() {
        return false;
    }

    /** All events overlapping the given day in the given timezone, in any order. */
    List<CalendarEvent> eventsOn(LocalDate day, ZoneId zone) throws Exception;
}
