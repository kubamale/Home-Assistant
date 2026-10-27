package dev.kuba.homedisplay.calendar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import dev.kuba.homedisplay.settings.LocationChangedEvent;
import dev.kuba.homedisplay.settings.TimeService;
import dev.kuba.homedisplay.stream.ChangePublisher;

/**
 * Keeps today's events cached and refreshed in the background. Clients are served from the cache;
 * the "next event" is worked out when read, so it stays right between refreshes.
 */
@Service
public class CalendarService {

    private static final Logger log = LoggerFactory.getLogger(CalendarService.class);
    private static final Duration STALE_AFTER = Duration.ofMinutes(20);

    private final CalendarSource source;
    private final TimeService time;
    private final ChangePublisher changes;
    private final Clock clock;

    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public CalendarService(CalendarSource source, TimeService time, ChangePublisher changes, Clock clock) {
        this.source = source;
        this.time = time;
        this.changes = changes;
        this.clock = clock;
    }

    private record Cached(LocalDate day, ZoneId zone, List<CalendarEvent> events, Instant fetchedAt,
            String lastError) {
    }

    /** What the API returns. */
    public record Snapshot(boolean configured, List<CalendarEvent> events, String nextEventId, Instant fetchedAt,
            boolean stale, String lastError) {
    }

    public Snapshot snapshot() {
        if (!source.configured()) {
            return new Snapshot(false, List.of(), null, null, false, null);
        }
        Cached cached = cache.get();
        if (cached == null || !cached.day().equals(time.today()) || !cached.zone().equals(time.zone())) {
            // never show yesterday's events, or events computed for another timezone
            return new Snapshot(true, List.of(), null, null, false, cached == null ? null : cached.lastError());
        }
        boolean stale = Duration.between(cached.fetchedAt(), clock.instant()).compareTo(STALE_AFTER) > 0;
        return new Snapshot(true, cached.events(), nextEventId(cached.events()), cached.fetchedAt(), stale,
                cached.lastError());
    }

    @Scheduled(initialDelayString = "PT8S", fixedDelayString = "PT5M")
    public void scheduledRefresh() {
        refresh();
    }

    @Async
    @EventListener
    public void onLocationChanged(LocationChangedEvent event) {
        refresh();
    }

    public synchronized void refresh() {
        if (!source.configured()) {
            return;
        }
        LocalDate day = time.today();
        ZoneId zone = time.zone();
        Cached previous = cache.get();
        try {
            List<CalendarEvent> events = source.eventsOn(day, zone).stream().sorted(ORDER).toList();
            boolean changed = previous == null || !previous.day().equals(day) || !previous.zone().equals(zone)
                    || !previous.events().equals(events) || previous.lastError() != null;
            cache.set(new Cached(day, zone, events, clock.instant(), null));
            if (changed) {
                changes.publish(ChangePublisher.CALENDAR);
            }
        } catch (Exception e) {
            log.warn("Calendar refresh failed: {}", e.toString());
            boolean usable = previous != null && previous.day().equals(day) && previous.zone().equals(zone);
            cache.set(new Cached(day, zone, usable ? previous.events() : List.of(),
                    usable ? previous.fetchedAt() : clock.instant(),
                    e.getMessage() == null ? e.toString() : e.getMessage()));
            changes.publish(ChangePublisher.CALENDAR);
        }
    }

    /** All-day events first, then by start time. */
    private static final Comparator<CalendarEvent> ORDER = Comparator
            .comparing((CalendarEvent e) -> !e.allDay())
            .thenComparing(e -> e.start() == null ? Instant.MIN : e.start())
            .thenComparing(CalendarEvent::title);

    /** The first timed event that has not finished yet: running now, or the next to start. */
    private String nextEventId(List<CalendarEvent> events) {
        Instant now = clock.instant();
        return events.stream()
                .filter(e -> !e.allDay() && e.end() != null && e.end().isAfter(now))
                .findFirst()
                .map(CalendarEvent::id)
                .orElse(null);
    }
}
