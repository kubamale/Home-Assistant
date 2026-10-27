package dev.kuba.homedisplay.weather;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import dev.kuba.homedisplay.config.AppProperties;
import dev.kuba.homedisplay.settings.Location;
import dev.kuba.homedisplay.settings.LocationChangedEvent;
import dev.kuba.homedisplay.settings.SettingsService;
import dev.kuba.homedisplay.stream.ChangePublisher;

/**
 * Fetches weather in the background and serves only cached data. A failed refresh keeps the last
 * good value; clients see how old it is.
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final WeatherClient client;
    private final SettingsService settings;
    private final ChangePublisher changes;
    private final Clock clock;
    private final Duration staleAfter;

    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public WeatherService(WeatherClient client, SettingsService settings, ChangePublisher changes, Clock clock,
            AppProperties props) {
        this.client = client;
        this.settings = settings;
        this.changes = changes;
        this.clock = clock;
        this.staleAfter = props.weather().staleAfter();
    }

    private record Cached(WeatherData data, Instant fetchedAt, String lastError, Location location) {
    }

    /** What the API returns. {@code configured} is false until the user has saved a location. */
    public record Snapshot(boolean configured, WeatherData data, Instant fetchedAt, boolean stale, String lastError) {
    }

    public Snapshot snapshot() {
        Optional<Location> location = settings.location();
        if (location.isEmpty()) {
            return new Snapshot(false, null, null, false, null);
        }
        Cached cached = cache.get();
        if (cached == null || !location.get().equals(cached.location())) {
            return new Snapshot(true, null, null, false, cached == null ? null : cached.lastError());
        }
        boolean stale = Duration.between(cached.fetchedAt(), clock.instant()).compareTo(staleAfter) > 0;
        return new Snapshot(true, cached.data(), cached.fetchedAt(), stale, cached.lastError());
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "${app.weather.refresh-interval:15m}")
    public void scheduledRefresh() {
        refresh();
    }

    @Async
    @EventListener
    public void onLocationChanged(LocationChangedEvent event) {
        refresh();
    }

    public synchronized void refresh() {
        Optional<Location> location = settings.location();
        if (location.isEmpty()) {
            return; // nothing to fetch until the user picks a place
        }
        Cached previous = cache.get();
        try {
            WeatherData data = client.fetch(location.get());
            cache.set(new Cached(data, clock.instant(), null, location.get()));
            changes.publish(ChangePublisher.WEATHER);
        } catch (RuntimeException e) {
            log.warn("Weather refresh failed: {}", e.toString());
            boolean sameLocation = previous != null && location.get().equals(previous.location());
            cache.set(new Cached(sameLocation ? previous.data() : null,
                    sameLocation ? previous.fetchedAt() : clock.instant(), e.getMessage() == null ? e.toString()
                            : e.getMessage(), location.get()));
            changes.publish(ChangePublisher.WEATHER);
        }
    }
}
