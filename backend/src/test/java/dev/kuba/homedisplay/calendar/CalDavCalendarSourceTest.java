package dev.kuba.homedisplay.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.kuba.homedisplay.config.AppProperties;

/** Runs the CalDAV client against a small fake server that answers the way iCloud does. */
class CalDavCalendarSourceTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final LocalDate DAY = LocalDate.parse("2026-10-24");

    private HttpServer server;
    private String base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> reportBodies = new ConcurrentHashMap<>();
    private volatile int reportStatus = 207;

    private static final String HOME_ICS = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//x//EN\n"
            + "BEGIN:VEVENT\nUID:h1\nDTSTAMP:20261001T000000Z\nDTSTART;TZID=Europe/Warsaw:20261024T113000\n"
            + "DTEND;TZID=Europe/Warsaw:20261024T123000\nSUMMARY:Dentist\nEND:VEVENT\nEND:VCALENDAR\n";
    private static final String WORK_ICS = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//x//EN\n"
            + "BEGIN:VEVENT\nUID:w1\nDTSTAMP:20261001T000000Z\nDTSTART;TZID=Europe/Warsaw:20261024T160000\n"
            + "DTEND;TZID=Europe/Warsaw:20261024T170000\nSUMMARY:Review &amp; plan\nEND:VEVENT\nEND:VCALENDAR\n";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String multistatus(String responses) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<multistatus xmlns=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">" + responses
                + "</multistatus>";
    }

    private static String calendarResponse(String href, String name, String component) {
        return "<response><href>" + href + "</href><propstat><prop><displayname>" + name + "</displayname>"
                + "<resourcetype><collection/><c:calendar/></resourcetype>"
                + "<c:supported-calendar-component-set><c:comp name=\"" + component + "\"/></c:supported-calendar-component-set>"
                + "</prop><status>HTTP/1.1 200 OK</status></propstat></response>";
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(method + " " + path);
        String expected = "Basic " + Base64.getEncoder().encodeToString("me@icloud.com:app-pass".getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        String xml;
        int status = 207;
        if (method.equals("PROPFIND") && path.equals("/")) {
            xml = multistatus("<response><href>/</href><propstat><prop><current-user-principal>"
                    + "<href>/123/principal/</href></current-user-principal></prop>"
                    + "<status>HTTP/1.1 200 OK</status></propstat></response>");
        } else if (method.equals("PROPFIND") && path.equals("/123/principal/")) {
            // iCloud sends the calendar home as an absolute URL, often on another host name
            xml = multistatus("<response><href>/123/principal/</href><propstat><prop>"
                    + "<c:calendar-home-set><href>" + base + "/123/calendars/</href></c:calendar-home-set></prop>"
                    + "<status>HTTP/1.1 200 OK</status></propstat></response>");
        } else if (method.equals("PROPFIND") && path.equals("/123/calendars/")) {
            xml = multistatus(
                    "<response><href>/123/calendars/</href><propstat><prop><displayname/><resourcetype><collection/></resourcetype></prop>"
                            + "<status>HTTP/1.1 200 OK</status></propstat></response>"
                            + "<response><href>/123/calendars/inbox/</href><propstat><prop><resourcetype><collection/><c:schedule-inbox/></resourcetype></prop>"
                            + "<status>HTTP/1.1 200 OK</status></propstat></response>"
                            + calendarResponse("/123/calendars/home/", "Home", "VEVENT")
                            + calendarResponse("/123/calendars/work/", "Work", "VEVENT")
                            + calendarResponse("/123/calendars/reminders/", "Reminders", "VTODO"));
        } else if (method.equals("REPORT")) {
            reportBodies.put(path, body);
            status = reportStatus;
            String ics = path.contains("/home/") ? HOME_ICS : path.contains("/work/") ? WORK_ICS : "";
            xml = multistatus("<response><href>" + path + "event.ics</href><propstat><prop>"
                    + "<getetag>\"1\"</getetag><c:calendar-data>" + ics + "</c:calendar-data></prop>"
                    + "<status>HTTP/1.1 200 OK</status></propstat></response>");
        } else {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private CalDavCalendarSource source(String password, String... calendars) {
        return new CalDavCalendarSource(
                new AppProperties.Calendar.CalDav(base + "/", "me@icloud.com", password, List.of(calendars)),
                HttpClient.newHttpClient());
    }

    @Test
    void discoversCalendarsAndReadsTodaysEventsFromAllOfThem() throws Exception {
        var events = source("app-pass").eventsOn(DAY, WARSAW);

        assertEquals(List.of("Dentist", "Review & plan"),
                events.stream().map(CalendarEvent::title).sorted().toList());
        assertEquals(List.of("Home", "Work"), events.stream().map(CalendarEvent::calendar).sorted().toList());
        // inbox and the reminders list (tasks only) are not queried
        assertTrue(requests.stream().noneMatch(r -> r.contains("inbox/") && r.startsWith("REPORT")));
        assertTrue(requests.stream().noneMatch(r -> r.contains("reminders") && r.startsWith("REPORT")));
    }

    @Test
    void asksOnlyForTheRequestedDayInUtc() throws Exception {
        source("app-pass").eventsOn(DAY, WARSAW);
        // 24 Oct in Warsaw (UTC+2) runs from 22:00 UTC on the 23rd to 22:00 UTC on the 24th
        String body = reportBodies.get("/123/calendars/home/");
        assertTrue(body.contains("start=\"20261023T220000Z\""), body);
        assertTrue(body.contains("end=\"20261024T220000Z\""), body);
    }

    @Test
    void canLimitTheCalendarsByName() throws Exception {
        var events = source("app-pass", "work").eventsOn(DAY, WARSAW);
        assertEquals(List.of("Review & plan"), events.stream().map(CalendarEvent::title).toList());
    }

    @Test
    void remembersTheCalendarListBetweenRefreshes() throws Exception {
        var source = source("app-pass");
        source.eventsOn(DAY, WARSAW);
        long discoveryCalls = requests.stream().filter(r -> r.startsWith("PROPFIND")).count();
        source.eventsOn(DAY, WARSAW);
        assertEquals(discoveryCalls, requests.stream().filter(r -> r.startsWith("PROPFIND")).count());
    }

    @Test
    void aWrongPasswordGivesAClearErrorWithoutLeakingIt() {
        var e = assertThrows(CalDavCalendarSource.CalDavException.class,
                () -> source("wrong-secret").eventsOn(DAY, WARSAW));
        assertTrue(e.getMessage().contains("username or app-specific password"));
        assertTrue(!e.getMessage().contains("wrong-secret"));
    }

    @Test
    void serverErrorsAreReportedAndDiscoveryIsRedoneAfterwards() throws Exception {
        var source = source("app-pass");
        source.eventsOn(DAY, WARSAW);
        reportStatus = 500;
        assertThrows(CalDavCalendarSource.CalDavException.class, () -> source.eventsOn(DAY, WARSAW));
        reportStatus = 207;
        long before = requests.stream().filter(r -> r.startsWith("PROPFIND")).count();
        assertEquals(2, source.eventsOn(DAY, WARSAW).size());
        assertTrue(requests.stream().filter(r -> r.startsWith("PROPFIND")).count() > before,
                "after an error the calendar list should be discovered again");
    }

    @Test
    void refusesToStartWithoutCredentials() {
        assertThrows(IllegalStateException.class, () -> new CalDavCalendarSource(
                new AppProperties.Calendar.CalDav(base, "", "", List.of()), HttpClient.newHttpClient()));
    }
}
