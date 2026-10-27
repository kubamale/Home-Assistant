package dev.kuba.homedisplay.settings;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.stereotype.Service;

/** One answer to "what day is it": the clock combined with the saved location's timezone. */
@Service
public class TimeService {

    private final Clock clock;
    private final SettingsService settings;

    public TimeService(Clock clock, SettingsService settings) {
        this.clock = clock;
        this.settings = settings;
    }

    public ZoneId zone() {
        return settings.zone();
    }

    public ZonedDateTime now() {
        return ZonedDateTime.now(clock).withZoneSameInstant(zone());
    }

    public LocalDate today() {
        return now().toLocalDate();
    }
}
