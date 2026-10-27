package dev.kuba.homedisplay.settings;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import dev.kuba.homedisplay.stream.ChangePublisher;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Stores user settings. Nothing about the location is hard-coded: it is whatever the user saved. */
@Service
public class SettingsService {

    private static final String LOCATION_KEY = "location";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final ChangePublisher changes;

    public SettingsService(JdbcClient jdbc, JsonMapper json, Clock clock, ApplicationEventPublisher events,
            ChangePublisher changes) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.events = events;
        this.changes = changes;
    }

    public Optional<Location> location() {
        return jdbc.sql("select value from setting where key = ?")
                .param(LOCATION_KEY)
                .query(String.class)
                .optional()
                .map(value -> {
                    try {
                        return json.readValue(value, Location.class);
                    } catch (JacksonException e) {
                        throw new IllegalStateException("Stored location is unreadable", e);
                    }
                });
    }

    /** The timezone of the saved location, or the server's own until a location is set. */
    public ZoneId zone() {
        return location().map(l -> ZoneId.of(l.timezone())).orElseGet(ZoneId::systemDefault);
    }

    public Location saveLocation(Location location) {
        validate(location);
        jdbc.sql("""
                insert into setting (key, value, updated_at) values (?, ?, ?)
                on conflict(key) do update set value = excluded.value, updated_at = excluded.updated_at""")
                .params(LOCATION_KEY, json.writeValueAsString(location), clock.instant().toString())
                .update();
        events.publishEvent(new LocationChangedEvent(location));
        changes.publish(ChangePublisher.SETTINGS);
        return location;
    }

    private static void validate(Location location) {
        if (location.name() == null || location.name().isBlank()) {
            throw bad("Location name is required");
        }
        if (location.latitude() < -90 || location.latitude() > 90) {
            throw bad("Latitude must be between -90 and 90");
        }
        if (location.longitude() < -180 || location.longitude() > 180) {
            throw bad("Longitude must be between -180 and 180");
        }
        try {
            ZoneId.of(location.timezone());
        } catch (DateTimeException | NullPointerException e) {
            throw bad("Unknown timezone: " + location.timezone());
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
