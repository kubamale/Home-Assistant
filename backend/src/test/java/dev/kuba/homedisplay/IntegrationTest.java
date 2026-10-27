package dev.kuba.homedisplay;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.kuba.homedisplay.devices.FakeDeviceAdapter;
import dev.kuba.homedisplay.security.AuthCookies;
import dev.kuba.homedisplay.security.LoginRateLimiter;
import dev.kuba.homedisplay.settings.GeocodingClient;
import dev.kuba.homedisplay.weather.WeatherClient;
import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTest.TestClock.class)
public abstract class IntegrationTest {

    static final Instant START = Instant.parse("2026-10-24T10:00:00Z");

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START);
        }
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected LoginRateLimiter limiter;
    @Autowired
    private FakeDeviceAdapter fakeDevices;

    @MockitoBean
    protected WeatherClient weatherClient;
    @MockitoBean
    protected GeocodingClient geocodingClient;

    @BeforeEach
    void resetState() {
        jdbc.sql("delete from task").update();
        jdbc.sql("delete from voice_turn").update();
        jdbc.sql("delete from quick_access").update();
        jdbc.sql("delete from scene_action").update();
        jdbc.sql("delete from scene").update();
        jdbc.sql("insert into scene (name, position, kind) values ('All off', 0, 'all_off')").update(); // the built-in scene
        jdbc.sql("delete from device").update();
        jdbc.sql("delete from room").update();
        fakeDevices.reset();
        fakeDevices.announce(); // the simulated lights are listed again, as the agent does on connect
        jdbc.sql("delete from credential").update();
        jdbc.sql("delete from setting").update();
        clock.set(START);
        limiter.success("login:127.0.0.1");
        limiter.success("pair-start:127.0.0.1");
    }

    /** Logs in as the user and returns the session cookie. */
    protected Cookie loginAsUser() throws Exception {
        return cookieFrom(mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse());
    }

    protected static Cookie cookieFrom(MockHttpServletResponse response) {
        Cookie cookie = response.getCookie(AuthCookies.NAME);
        if (cookie == null) {
            throw new AssertionError("No session cookie in response");
        }
        return cookie;
    }

    /** Pairs a door screen through the full flow and returns its cookie. */
    protected Cookie pairDisplay(Cookie user) throws Exception {
        String start = mvc.perform(post("/api/auth/pair/start")).andReturn().getResponse().getContentAsString();
        String deviceCode = start.replaceAll(".*\"deviceCode\":\"([^\"]+)\".*", "$1");
        String userCode = start.replaceAll(".*\"userCode\":\"([^\"]+)\".*", "$1");
        mvc.perform(post("/api/auth/pair/approve").cookie(user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userCode\":\"" + userCode + "\",\"label\":\"Test door\"}"))
                .andExpect(status().isOk());
        return cookieFrom(mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/pair/poll")
                        .param("deviceCode", deviceCode))
                .andExpect(status().isOk())
                .andReturn().getResponse());
    }
}
