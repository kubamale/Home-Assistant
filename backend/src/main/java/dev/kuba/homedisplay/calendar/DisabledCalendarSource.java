package dev.kuba.homedisplay.calendar;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.calendar.mode", havingValue = "off", matchIfMissing = true)
public class DisabledCalendarSource implements CalendarSource {

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public List<CalendarEvent> eventsOn(LocalDate day, ZoneId zone) {
        return List.of();
    }
}
