package dev.kuba.homedisplay.calendar;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import dev.kuba.homedisplay.config.AppProperties;

/**
 * Reads events from an iCloud (or any CalDAV) account with an app-specific password. Discovery follows
 * the usual CalDAV steps: user principal, then calendar home, then the calendars in it. Only reads;
 * nothing is ever written to the calendar.
 */
@Component
@ConditionalOnProperty(name = "app.calendar.mode", havingValue = "caldav")
public class CalDavCalendarSource implements CalendarSource {

    private static final Logger log = LoggerFactory.getLogger(CalDavCalendarSource.class);
    private static final String DAV = "DAV:";
    private static final String CALDAV = "urn:ietf:params:xml:ns:caldav";
    private static final Duration CALENDAR_LIST_TTL = Duration.ofHours(1);
    private static final DateTimeFormatter UTC_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    /** Thrown for any CalDAV problem. Messages never contain the password. */
    public static class CalDavException extends IOException {
        public CalDavException(String message) {
            super(message);
        }
    }

    record CalendarInfo(URI url, String name) {
    }

    private final URI baseUrl;
    private final String authorization;
    private final List<String> include;
    private final HttpClient http;

    private volatile List<CalendarInfo> calendars;
    private volatile Instant calendarsFetchedAt = Instant.EPOCH;

    @Autowired
    public CalDavCalendarSource(AppProperties props) {
        this(props.calendar().caldav(), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    CalDavCalendarSource(AppProperties.Calendar.CalDav config, HttpClient http) {
        if (config == null || isBlank(config.username()) || isBlank(config.password())) {
            throw new IllegalStateException(
                    "Calendar mode is caldav: set app.calendar.caldav.username and password (an app-specific password)");
        }
        this.baseUrl = URI.create(config.url());
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((config.username() + ":" + config.password()).getBytes(StandardCharsets.UTF_8));
        this.include = config.calendars() == null ? List.of()
                : config.calendars().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.http = http;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public List<CalendarEvent> eventsOn(LocalDate day, ZoneId zone) throws Exception {
        Instant start = day.atStartOfDay(zone).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
        List<CalendarEvent> events = new ArrayList<>();
        try {
            for (CalendarInfo calendar : calendars()) {
                for (String ics : query(calendar, start, end)) {
                    try {
                        events.addAll(ICalExpander.expand(ics, calendar.name(), day, zone));
                    } catch (Exception e) {
                        // one unreadable event must not hide the rest of the day
                        log.warn("Skipping an unreadable event in '{}': {}", calendar.name(), e.toString());
                    }
                }
            }
        } catch (IOException e) {
            calendars = null; // rediscover next time, in case the account's calendars moved
            throw e;
        }
        return events;
    }

    // ---- discovery ----

    private List<CalendarInfo> calendars() throws IOException {
        List<CalendarInfo> cached = calendars;
        if (cached != null && Duration.between(calendarsFetchedAt, Instant.now()).compareTo(CALENDAR_LIST_TTL) < 0) {
            return cached;
        }
        URI principal = hrefProperty(baseUrl, "<d:current-user-principal/>", DAV, "current-user-principal")
                .orElseThrow(() -> new CalDavException("The server did not say who the user is"));
        URI home = hrefProperty(principal, "<c:calendar-home-set/>", CALDAV, "calendar-home-set")
                .orElseThrow(() -> new CalDavException("The server did not list a calendar home"));
        List<CalendarInfo> found = listCalendars(home);
        log.info("Found {} calendars on the CalDAV account", found.size());
        calendars = found;
        calendarsFetchedAt = Instant.now();
        return found;
    }

    /** PROPFIND for one property whose value is a single href, resolved against the request URL. */
    private Optional<URI> hrefProperty(URI url, String propXml, String namespace, String localName) throws IOException {
        Document doc = send("PROPFIND", url, 0, """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop>%s</d:prop></d:propfind>"""
                .formatted(propXml));
        for (Element property : elements(doc, namespace, localName)) {
            for (Element href : elements(property, DAV, "href")) {
                String text = href.getTextContent().trim();
                if (!text.isEmpty()) {
                    return Optional.of(url.resolve(text));
                }
            }
        }
        return Optional.empty();
    }

    private List<CalendarInfo> listCalendars(URI home) throws IOException {
        Document doc = send("PROPFIND", home, 1, """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop><d:displayname/><d:resourcetype/><c:supported-calendar-component-set/></d:prop>
                </d:propfind>""");
        List<CalendarInfo> out = new ArrayList<>();
        for (Element response : elements(doc, DAV, "response")) {
            boolean isCalendar = !elements(response, CALDAV, "calendar").isEmpty()
                    && !elements(response, DAV, "resourcetype").isEmpty();
            if (!isCalendar) {
                continue; // inbox, outbox, notification collections and so on
            }
            List<Element> components = elements(response, CALDAV, "comp");
            boolean holdsEvents = components.isEmpty()
                    || components.stream().anyMatch(c -> "VEVENT".equalsIgnoreCase(c.getAttribute("name")));
            if (!holdsEvents) {
                continue; // reminder lists only hold tasks
            }
            String href = elements(response, DAV, "href").stream().findFirst().map(e -> e.getTextContent().trim())
                    .orElse("");
            String name = elements(response, DAV, "displayname").stream().findFirst()
                    .map(e -> e.getTextContent().trim()).filter(s -> !s.isEmpty()).orElse("Calendar");
            if (href.isEmpty() || (!include.isEmpty() && !include.contains(name.toLowerCase(Locale.ROOT)))) {
                continue;
            }
            out.add(new CalendarInfo(home.resolve(href), name));
        }
        return out;
    }

    // ---- queries ----

    /** Returns the iCalendar text of every event overlapping the time range. */
    private List<String> query(CalendarInfo calendar, Instant start, Instant end) throws IOException {
        Document doc = send("REPORT", calendar.url(), 1, """
                <?xml version="1.0" encoding="utf-8"?>
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop><d:getetag/><c:calendar-data/></d:prop>
                  <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT">
                    <c:time-range start="%s" end="%s"/>
                  </c:comp-filter></c:comp-filter></c:filter>
                </c:calendar-query>""".formatted(UTC_STAMP.format(start), UTC_STAMP.format(end)));
        List<String> out = new ArrayList<>();
        for (Element data : elements(doc, CALDAV, "calendar-data")) {
            String text = data.getTextContent();
            if (text != null && !text.isBlank()) {
                out.add(text);
            }
        }
        return out;
    }

    // ---- HTTP and XML ----

    private Document send(String method, URI url, int depth, String body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", authorization)
                .header("Depth", String.valueOf(depth))
                .header("Content-Type", "application/xml; charset=utf-8")
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CalDavException("Interrupted");
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new CalDavException("The calendar server rejected the username or app-specific password");
        }
        if (status != 207) {
            throw new CalDavException("The calendar server answered " + status + " to " + method);
        }
        return parse(response.body());
    }

    private static Document parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // calendar servers are not trusted to send safe XML
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException e) {
            throw new CalDavException("The calendar server sent an unreadable response");
        }
    }

    private static List<Element> elements(Node root, String namespace, String localName) {
        NodeList nodes = root instanceof Document d
                ? d.getElementsByTagNameNS(namespace, localName)
                : ((Element) root).getElementsByTagNameNS(namespace, localName);
        List<Element> out = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
