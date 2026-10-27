package dev.kuba.homedisplay;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import dev.kuba.homedisplay.calendar.CalendarService;
import jakarta.servlet.http.Cookie;

class CalendarTest extends IntegrationTest {

    private static final String WARSAW = """
            {"name":"Kraków","latitude":50.06,"longitude":19.94,"timezone":"Europe/Warsaw"}""";

    @Autowired
    CalendarService calendar;

    @Test
    void showsTodaysEventsAllDayFirstAndHighlightsTheRunningOrNextOne() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(put("/api/settings/location").cookie(user).contentType(MediaType.APPLICATION_JSON).content(WARSAW));
        calendar.refresh();

        // 10:00 UTC is 12:00 in Warsaw: the dentist (11:30-12:30) is running
        mvc.perform(get("/api/calendar/today").cookie(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured", is(true)))
                .andExpect(jsonPath("$.events", hasSize(5)))
                .andExpect(jsonPath("$.events[*].id",
                        contains("fake-1", "fake-2", "fake-3", "fake-4", "fake-5")))
                .andExpect(jsonPath("$.events[0].allDay", is(true)))
                .andExpect(jsonPath("$.events[0].date", is("2026-10-24")))
                .andExpect(jsonPath("$.nextEventId", is("fake-3")))
                .andExpect(jsonPath("$.stale", is(false)));

        clock.set(Instant.parse("2026-10-24T10:45:00Z")); // 12:45 local: the dentist is over
        mvc.perform(get("/api/calendar/today").cookie(user)).andExpect(jsonPath("$.nextEventId", is("fake-4")));

        clock.set(Instant.parse("2026-10-24T16:00:00Z")); // 18:00 local: nothing left today
        mvc.perform(get("/api/calendar/today").cookie(user))
                .andExpect(jsonPath("$.nextEventId").doesNotExist());
    }

    @Test
    void neverShowsYesterdaysEventsAfterMidnight() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(put("/api/settings/location").cookie(user).contentType(MediaType.APPLICATION_JSON).content(WARSAW));
        calendar.refresh();

        clock.set(Instant.parse("2026-10-24T22:30:00Z")); // 00:30 on the 25th in Warsaw
        mvc.perform(get("/api/calendar/today").cookie(user)).andExpect(jsonPath("$.events", hasSize(0)));

        calendar.refresh();
        mvc.perform(get("/api/calendar/today").cookie(user)).andExpect(jsonPath("$.events", hasSize(5)))
                .andExpect(jsonPath("$.events[0].date", is("2026-10-25")));
    }

    @Test
    void displayCanReadItToo() throws Exception {
        Cookie display = pairDisplay(loginAsUser());
        mvc.perform(get("/api/calendar/today").cookie(display)).andExpect(status().isOk());
    }
}
