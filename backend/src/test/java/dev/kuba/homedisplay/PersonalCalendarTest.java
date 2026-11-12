package dev.kuba.homedisplay;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.kuba.homedisplay.calendar.CalendarService;
import jakarta.servlet.http.Cookie;

/** A person connects their own calendar; it is the one they and their display see, and the password is kept encrypted. */
class PersonalCalendarTest extends IntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String ICS = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//x//EN\n"
            + "BEGIN:VEVENT\nUID:a1\nDTSTAMP:20261001T000000Z\nDTSTART;TZID=Europe/Warsaw:20261024T143000\n"
            + "DTEND;TZID=Europe/Warsaw:20261024T153000\nSUMMARY:Annas piano lesson\nEND:VEVENT\nEND:VCALENDAR\n";

    @Autowired
    CalendarService calendar;

    private HttpServer server;
    private String base;

    @BeforeEach
    void startFakeCalDav() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopFakeCalDav() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        ex.getRequestBody().readAllBytes();
        String expected = "Basic " + Base64.getEncoder().encodeToString("anna@icloud.com:anna-app-pass".getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        String xml;
        if (method.equals("PROPFIND") && path.equals("/")) {
            xml = "<multistatus xmlns=\"DAV:\"><response><href>/</href><propstat><prop><current-user-principal><href>/p/</href>"
                    + "</current-user-principal></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>";
        } else if (method.equals("PROPFIND") && path.equals("/p/")) {
            xml = "<multistatus xmlns=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><response><href>/p/</href><propstat><prop>"
                    + "<c:calendar-home-set><href>/cals/</href></c:calendar-home-set></prop><status>HTTP/1.1 200 OK</status>"
                    + "</propstat></response></multistatus>";
        } else if (method.equals("PROPFIND") && path.equals("/cals/")) {
            xml = "<multistatus xmlns=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><response><href>/cals/music/</href>"
                    + "<propstat><prop><displayname>Music</displayname><resourcetype><collection/><c:calendar/></resourcetype>"
                    + "<c:supported-calendar-component-set><c:comp name=\"VEVENT\"/></c:supported-calendar-component-set></prop>"
                    + "<status>HTTP/1.1 200 OK</status></propstat></response></multistatus>";
        } else if (method.equals("REPORT")) {
            xml = "<multistatus xmlns=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><response><href>" + path
                    + "e.ics</href><propstat><prop><getetag>\"1\"</getetag><c:calendar-data>" + ICS
                    + "</c:calendar-data></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>";
        } else {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(207, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private void setLocationToWarsaw(Cookie admin) throws Exception {
        mvc.perform(put("/api/settings/location").cookie(admin).contentType(JSON)
                .content("{\"name\":\"Kraków\",\"latitude\":50.06,\"longitude\":19.94,\"timezone\":\"Europe/Warsaw\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aConnectedCalendarIsThePersonsOwnAndTheirDisplaysAndTheOthersKeepTheirs() throws Exception {
        Cookie admin = loginAsUser();
        setLocationToWarsaw(admin);
        long anna = createUser(admin, "Anna", "anna-password");
        Cookie annaCookie = loginAs("Anna", "anna-password");
        Cookie annasDisplay = pairDisplay(annaCookie);

        calendar.connect(anna, base, "anna@icloud.com", "anna-app-pass", List.of());

        mvc.perform(get("/api/calendar/today").cookie(annaCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured", is(true)))
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.events[0].title", is("Annas piano lesson")));
        mvc.perform(get("/api/calendar/today").cookie(annasDisplay))
                .andExpect(jsonPath("$.events[0].title", is("Annas piano lesson")));
        // the admin still sees the server's (here: the sample) calendar, not Anna's
        calendar.refresh();
        mvc.perform(get("/api/calendar/today").cookie(admin)).andExpect(jsonPath("$.events[*].title", not(contains("Annas piano lesson"))));

        // what the app tells about the connection never includes the password
        mvc.perform(get("/api/calendar/connection").cookie(annaCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.connected", is(true)))
                .andExpect(jsonPath("$.username", is("anna@icloud.com")))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void thePasswordIsStoredEncrypted() throws Exception {
        Cookie admin = loginAsUser();
        setLocationToWarsaw(admin);
        long anna = createUser(admin, "Anna", "anna-password");
        calendar.connect(anna, base, "anna@icloud.com", "anna-app-pass", List.of("Music"));

        String stored = jdbc.sql("select password from user_calendar where user_id = ?").param(anna).query(String.class).single();
        assertFalse(stored.contains("anna-app-pass"));
        assertTrue(stored.length() > 20);
        assertEquals("[\"Music\"]", jdbc.sql("select calendars from user_calendar where user_id = ?").param(anna)
                .query(String.class).single());
    }

    @Test
    void aWrongPasswordIsNotSavedAndTheMessageDoesNotRevealIt() throws Exception {
        Cookie admin = loginAsUser();
        setLocationToWarsaw(admin);
        long anna = createUser(admin, "Anna", "anna-password");

        Exception failure = assertThrows(Exception.class,
                () -> calendar.connect(anna, base, "anna@icloud.com", "not-the-password", List.of()));
        assertFalse(String.valueOf(failure.getMessage()).contains("not-the-password"));
        assertEquals(0, jdbc.sql("select count(*) from user_calendar").query(Integer.class).single());
    }

    @Test
    void disconnectingGoesBackToWhatThePersonHadBefore() throws Exception {
        Cookie admin = loginAsUser();
        setLocationToWarsaw(admin);
        long anna = createUser(admin, "Anna", "anna-password");
        Cookie annaCookie = loginAs("Anna", "anna-password");
        calendar.connect(anna, base, "anna@icloud.com", "anna-app-pass", List.of());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/calendar/connection").cookie(annaCookie))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/calendar/connection").cookie(annaCookie)).andExpect(jsonPath("$.connected", is(false)));
        calendar.refresh();
        mvc.perform(get("/api/calendar/today").cookie(annaCookie))
                .andExpect(jsonPath("$.events[*].title", not(contains("Annas piano lesson"))));
    }

    @Test
    void theApiRefusesPlainHttpAndAddressesOnTheServerItself() throws Exception {
        Cookie admin = loginAsUser();
        String password = "\"username\":\"anna@icloud.com\",\"password\":\"anna-app-pass\"";
        // not https
        mvc.perform(put("/api/calendar/connection").cookie(admin).contentType(JSON)
                .content("{\"url\":\"" + base + "\"," + password + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("https")));
        // https, but the server itself (and the cloud metadata address)
        for (String url : new String[] {"https://127.0.0.1:9/", "https://localhost/", "https://[::1]/", "https://169.254.169.254/"}) {
            mvc.perform(put("/api/calendar/connection").cookie(admin).contentType(JSON)
                    .content("{\"url\":\"" + url + "\"," + password + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail", containsString("not allowed")))
                    .andExpect(jsonPath("$.detail", not(containsString("anna-app-pass"))));
        }
        assertEquals(0, jdbc.sql("select count(*) from user_calendar").query(Integer.class).single());
    }

    @Test
    void aSavedPasswordThatCannotBeReadDoesNotBreakAnything() throws Exception {
        Cookie admin = loginAsUser();
        setLocationToWarsaw(admin);
        long anna = createUser(admin, "Anna", "anna-password");
        Cookie annaCookie = loginAs("Anna", "anna-password");
        calendar.connect(anna, base, "anna@icloud.com", "anna-app-pass", List.of());
        // as if the secret key had been changed since: the stored value is no longer readable
        jdbc.sql("update user_calendar set password = 'bm90IHJlYWxseSBlbmNyeXB0ZWQ=' where user_id = ?").param(anna).update();

        calendar.refresh(); // must not throw, and must not stop others being refreshed
        mvc.perform(get("/api/calendar/connection").cookie(annaCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.connected", is(false)));
        mvc.perform(get("/api/calendar/today").cookie(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/calendar/today").cookie(annaCookie)).andExpect(status().isOk());
    }
}
