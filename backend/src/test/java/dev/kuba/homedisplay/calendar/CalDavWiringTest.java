package dev.kuba.homedisplay.calendar;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Starts the whole application in caldav mode, which the unit tests of the source never do. */
@SpringBootTest(properties = {
        "app.calendar.mode=caldav",
        "app.calendar.caldav.url=http://localhost:1/",
        "app.calendar.caldav.username=someone@example.com",
        "app.calendar.caldav.password=app-specific-password"})
@ActiveProfiles("test")
class CalDavWiringTest {

    @Autowired
    CalendarSource source;

    @Test
    void contextStartsWithTheCalDavSource() {
        assertInstanceOf(CalDavCalendarSource.class, source);
    }
}
