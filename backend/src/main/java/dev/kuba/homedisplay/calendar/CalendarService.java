package dev.kuba.homedisplay.calendar;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import dev.kuba.homedisplay.config.AppProperties;
import dev.kuba.homedisplay.settings.LocationChangedEvent;
import dev.kuba.homedisplay.settings.TimeService;
import dev.kuba.homedisplay.stream.ChangePublisher;
import dev.kuba.homedisplay.users.AppUser;
import dev.kuba.homedisplay.users.UserService;

/**
 * Keeps each person's events for today cached and refreshed in the background. A person sees the calendar they
 * connected themselves; the admin, until they connect one, sees the account set up on the server. Clients are served
 * from the cache; the "next event" is worked out when read, so it stays right between refreshes.
 */
@Service
public class CalendarService {

    private static final Logger log = LoggerFactory.getLogger(CalendarService.class);
    private static final Duration STALE_AFTER = Duration.ofMinutes(20);
    /** The cache key of the account configured on the server. People's own use their id. */
    private static final long SERVER = 0;

    private final CalendarSource serverSource;
    private final UserCalendarStore store;
    private final UserService users;
    private final TimeService time;
    private final ChangePublisher changes;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Map<Long, Slot> slots = new ConcurrentHashMap<>();

    public CalendarService(CalendarSource serverSource, UserCalendarStore store, UserService users, TimeService time,
            ChangePublisher changes, Clock clock) {
        this.serverSource = serverSource;
        this.store = store;
        this.users = users;
        this.time = time;
        this.changes = changes;
        this.clock = clock;
    }

    private record Cached(LocalDate day, ZoneId zone, List<CalendarEvent> events, Instant fetchedAt, String lastError) {
    }

    /** One calendar being kept: where it comes from (and which saved connection that was) and what was last read. */
    private static final class Slot {
        final CalendarSource source;
        final String version;
        volatile Cached cache;

        Slot(CalendarSource source, String version) {
            this.source = source;
            this.version = version;
        }
    }

    /** What the API returns. */
    public record Snapshot(boolean configured, List<CalendarEvent> events, String nextEventId, Instant fetchedAt,
            boolean stale, String lastError) {
    }

    /** How the person's calendar is set up. The password is never given out. */
    public record Connection(boolean connected, boolean serverAccount, String url, String username, List<String> calendars) {
    }

    public Snapshot snapshot(long userId) {
        Slot slot = slotFor(userId);
        if (slot == null || !slot.source.configured()) {
            return new Snapshot(false, List.of(), null, null, false, null);
        }
        Cached cached = slot.cache;
        if (cached == null || !cached.day().equals(time.today()) || !cached.zone().equals(time.zone())) {
            // never show yesterday's events, or events computed for another timezone
            return new Snapshot(true, List.of(), null, null, false, cached == null ? null : cached.lastError());
        }
        boolean stale = Duration.between(cached.fetchedAt(), clock.instant()).compareTo(STALE_AFTER) > 0;
        return new Snapshot(true, cached.events(), nextEventId(cached.events()), cached.fetchedAt(), stale,
                cached.lastError());
    }

    public Connection connection(long userId) {
        return store.find(userId)
                .map(c -> new Connection(true, false, c.url(), c.username(), c.calendars()))
                .orElseGet(() -> new Connection(false, usesServerAccount(userId), null, null, List.of()));
    }

    /**
     * Connects a person's own calendar after checking that it can be read. Throws {@link CalDavCalendarSource.CalDavException}
     * with a message that never contains the password when it cannot.
     */
    public void connect(long userId, String url, String username, String password, List<String> calendarNames) throws Exception {
        CalDavCalendarSource probe = new CalDavCalendarSource(
                new AppProperties.Calendar.CalDav(url, username, password, calendarNames), http);
        probe.eventsOn(time.today(), time.zone()); // throws if the account or the password is wrong
        store.save(userId, url, username, password, calendarNames);
        slots.remove(userId);
        refreshKey(userId); // so the first look at the display already has the events
    }

    public void disconnect(long userId) {
        store.delete(userId);
        slots.remove(userId);
        changes.publish(ChangePublisher.CALENDAR);
    }

    /** Reads the calendars again: everyone's. */
    public void refresh() {
        List<Long> keys = new ArrayList<>(store.usersWithCalendars());
        keys.add(SERVER);
        for (long key : keys) {
            try {
                refreshKey(key);
            } catch (RuntimeException e) {
                log.warn("Refreshing the calendar of {} failed: {}", key, e.toString()); // one person's problem must not stop the rest
            }
        }
    }

    @Scheduled(initialDelayString = "${app.calendar.initial-delay:PT8S}", fixedDelayString = "PT5M")
    public void scheduledRefresh() {
        refresh();
    }

    @Async
    @EventListener
    public void onLocationChanged(LocationChangedEvent event) {
        refresh();
    }

    private void refreshKey(long key) {
        Slot slot = key == SERVER ? serverSlot() : slotFor(key);
        if (slot == null || !slot.source.configured()) {
            return;
        }
        synchronized (slot) {
            LocalDate day = time.today();
            ZoneId zone = time.zone();
            Cached previous = slot.cache;
            try {
                List<CalendarEvent> events = slot.source.eventsOn(day, zone).stream().sorted(ORDER).toList();
                boolean changed = previous == null || !previous.day().equals(day) || !previous.zone().equals(zone)
                        || !previous.events().equals(events) || previous.lastError() != null;
                slot.cache = new Cached(day, zone, events, clock.instant(), null);
                if (changed) {
                    changes.publish(ChangePublisher.CALENDAR);
                }
            } catch (Exception e) {
                log.warn("Calendar refresh failed: {}", e.toString());
                boolean usable = previous != null && previous.day().equals(day) && previous.zone().equals(zone);
                slot.cache = new Cached(day, zone, usable ? previous.events() : List.of(),
                        usable ? previous.fetchedAt() : clock.instant(),
                        e.getMessage() == null ? e.toString() : e.getMessage());
                changes.publish(ChangePublisher.CALENDAR);
            }
        }
    }

    /** The person's own connection if they made one, else the server's account for the admin (or for all, in the sample calendar). */
    private Slot slotFor(long userId) {
        var own = store.find(userId);
        if (own.isPresent()) {
            var c = own.get();
            Slot slot = slots.get(userId);
            if (slot == null || !slot.version.equals(c.updatedAt())) {
                slot = new Slot(new CalDavCalendarSource(
                        new AppProperties.Calendar.CalDav(c.url(), c.username(), c.password(), c.calendars()), http),
                        c.updatedAt());
                slots.put(userId, slot);
            }
            return slot;
        }
        slots.remove(userId);
        return usesServerAccount(userId) ? serverSlot() : null;
    }

    private Slot serverSlot() {
        return slots.computeIfAbsent(SERVER, k -> new Slot(serverSource, "server"));
    }

    private boolean usesServerAccount(long userId) {
        if (!serverSource.configured()) {
            return false;
        }
        return serverSource.sharedWithEveryone() || users.find(userId).map(AppUser::admin).orElse(false);
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
