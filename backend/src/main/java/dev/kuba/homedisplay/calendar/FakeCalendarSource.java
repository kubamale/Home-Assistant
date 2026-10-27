package dev.kuba.homedisplay.calendar;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** A plausible day of events so the UI can be built without an iCloud account. */
@Component
@ConditionalOnProperty(name = "app.calendar.mode", havingValue = "fake")
public class FakeCalendarSource implements CalendarSource {

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public List<CalendarEvent> eventsOn(LocalDate day, ZoneId zone) {
        return List.of(
                new CalendarEvent("fake-1", "Trash pickup", true, null, null, day, null, "Home"),
                timed(day, zone, "fake-2", "Stand-up", 9, 0, 9, 15, "Video call", "Work"),
                timed(day, zone, "fake-3", "Dentist", 11, 30, 12, 30, "Floriańska 12", "Personal"),
                timed(day, zone, "fake-4", "Lunch with Anna", 13, 0, 14, 0, null, "Personal"),
                timed(day, zone, "fake-5", "Project review", 16, 0, 17, 0, "Room 4", "Work"));
    }

    private static CalendarEvent timed(LocalDate day, ZoneId zone, String id, String title, int sh, int sm, int eh,
            int em, String location, String calendar) {
        return new CalendarEvent(id, title, false,
                day.atTime(LocalTime.of(sh, sm)).atZone(zone).toInstant(),
                day.atTime(LocalTime.of(eh, em)).atZone(zone).toInstant(),
                null, location, calendar);
    }
}
