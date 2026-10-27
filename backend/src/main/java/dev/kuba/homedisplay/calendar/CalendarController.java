package dev.kuba.homedisplay.calendar;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CalendarController {

    private final CalendarService calendar;

    public CalendarController(CalendarService calendar) {
        this.calendar = calendar;
    }

    @GetMapping("/api/calendar/today")
    public CalendarService.Snapshot today() {
        return calendar.snapshot();
    }
}
