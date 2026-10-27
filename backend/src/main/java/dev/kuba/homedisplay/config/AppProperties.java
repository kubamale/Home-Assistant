package dev.kuba.homedisplay.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app")
public record AppProperties(Auth auth, Weather weather, Calendar calendar, Devices devices) {

    public record Auth(
            String passwordHash,
            String password,
            @DefaultValue("90") int sessionDays,
            @DefaultValue("true") boolean secureCookies) {
    }

    public record Weather(
            @DefaultValue("https://api.open-meteo.com/v1/forecast") String forecastUrl,
            @DefaultValue("https://geocoding-api.open-meteo.com/v1/search") String geocodingUrl,
            @DefaultValue("15m") Duration refreshInterval) {

        /** Cached data older than this is flagged stale for clients. */
        public Duration staleAfter() {
            return refreshInterval.multipliedBy(2);
        }
    }

    /** {@code mode} is off, fake or caldav. The caldav settings are only needed in caldav mode. */
    public record Calendar(@DefaultValue("off") String mode, CalDav caldav) {

        public record CalDav(
                @DefaultValue("https://caldav.icloud.com/") String url,
                String username,
                String password,
                /** Only show calendars with these names; empty means all of them. */
                @DefaultValue List<String> calendars) {
        }
    }

    public record Devices(@DefaultValue("fake") String mode, @DefaultValue("5s") Duration commandTimeout) {
    }
}
