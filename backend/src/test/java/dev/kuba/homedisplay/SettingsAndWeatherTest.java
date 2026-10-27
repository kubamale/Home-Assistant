package dev.kuba.homedisplay;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;

import dev.kuba.homedisplay.settings.Place;
import dev.kuba.homedisplay.settings.TimeService;
import dev.kuba.homedisplay.weather.WeatherData;
import dev.kuba.homedisplay.weather.WeatherService;
import jakarta.servlet.http.Cookie;

class SettingsAndWeatherTest extends IntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String KRAKOW = """
            {"name":"Kraków","latitude":50.06,"longitude":19.94,"timezone":"Europe/Warsaw"}""";

    @Autowired
    WeatherService weatherService;
    @Autowired
    TimeService time;

    private static WeatherData sample(String place) {
        return new WeatherData(place,
                new WeatherData.Current(12.5, 11.0, "cloudy", "Overcast", 14.0, 0.0, true),
                new WeatherData.Today(8.0, 14.0, 40, "rain", "Rain", "2026-10-24T06:50", "2026-10-24T16:30"),
                List.of(new WeatherData.Hour("2026-10-24T12:00", 12.5, 10, "cloudy")));
    }

    @Test
    void noLocationMeansNothingIsConfiguredAndNothingIsFetched() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(get("/api/settings").cookie(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.location", nullValue()));
        mvc.perform(get("/api/weather").cookie(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured", is(false)))
                .andExpect(jsonPath("$.data", nullValue()));

        weatherService.refresh();
        verify(weatherClient, never()).fetch(any());
    }

    @Test
    void savedLocationIsUsedForWeatherAndDefinesTheTimezone() throws Exception {
        Cookie user = loginAsUser();
        when(weatherClient.fetch(any())).thenReturn(sample("Kraków"));

        mvc.perform(put("/api/settings/location").cookie(user).contentType(JSON).content(KRAKOW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location.timezone", is("Europe/Warsaw")));
        mvc.perform(get("/api/settings").cookie(user))
                .andExpect(jsonPath("$.location.name", is("Kraków")))
                .andExpect(jsonPath("$.location.latitude", is(50.06)));

        weatherService.refresh();
        mvc.perform(get("/api/weather").cookie(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured", is(true)))
                .andExpect(jsonPath("$.stale", is(false)))
                .andExpect(jsonPath("$.data.locationName", is("Kraków")))
                .andExpect(jsonPath("$.data.current.temperature", is(12.5)))
                .andExpect(jsonPath("$.data.today.condition", is("rain")))
                .andExpect(jsonPath("$.fetchedAt").exists());

        // "today" follows the saved timezone: 23:30 UTC is already tomorrow in Warsaw
        clock.set(java.time.Instant.parse("2026-10-24T23:30:00Z"));
        org.junit.jupiter.api.Assertions.assertEquals(java.time.LocalDate.parse("2026-10-25"), time.today());
    }

    @Test
    void invalidLocationsAreRejected() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(put("/api/settings/location").cookie(user).contentType(JSON)
                .content("{\"name\":\"X\",\"latitude\":50,\"longitude\":19,\"timezone\":\"Mars/Base\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/location").cookie(user).contentType(JSON)
                .content("{\"name\":\"X\",\"latitude\":950,\"longitude\":19,\"timezone\":\"UTC\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/location").cookie(user).contentType(JSON)
                .content("{\"name\":\"\",\"latitude\":50,\"longitude\":19,\"timezone\":\"UTC\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/settings").cookie(user)).andExpect(jsonPath("$.location", nullValue()));
    }

    @Test
    void failedRefreshKeepsTheLastGoodDataAndReportsTheError() throws Exception {
        Cookie user = loginAsUser();
        when(weatherClient.fetch(any())).thenReturn(sample("Kraków"));
        mvc.perform(put("/api/settings/location").cookie(user).contentType(JSON).content(KRAKOW));
        weatherService.refresh();

        when(weatherClient.fetch(any())).thenThrow(new RestClientException("timeout"));
        clock.set(START.plusSeconds(10 * 60));
        weatherService.refresh();

        mvc.perform(get("/api/weather").cookie(user))
                .andExpect(jsonPath("$.data.locationName", is("Kraków")))
                .andExpect(jsonPath("$.lastError", is("timeout")))
                .andExpect(jsonPath("$.stale", is(false)));

        clock.set(START.plusSeconds(45 * 60)); // older than twice the 15 minute refresh interval
        mvc.perform(get("/api/weather").cookie(user)).andExpect(jsonPath("$.stale", is(true)));
    }

    @Test
    void changingTheLocationDropsDataThatBelongedToTheOldPlace() throws Exception {
        Cookie user = loginAsUser();
        when(weatherClient.fetch(any())).thenReturn(sample("Kraków"));
        mvc.perform(put("/api/settings/location").cookie(user).contentType(JSON).content(KRAKOW));
        weatherService.refresh();

        when(weatherClient.fetch(any())).thenThrow(new RestClientException("down"));
        mvc.perform(put("/api/settings/location").cookie(user).contentType(JSON)
                .content("{\"name\":\"Oslo\",\"latitude\":59.9,\"longitude\":10.7,\"timezone\":\"Europe/Oslo\"}"));
        weatherService.refresh();

        mvc.perform(get("/api/weather").cookie(user))
                .andExpect(jsonPath("$.configured", is(true)))
                .andExpect(jsonPath("$.data", nullValue()));
    }

    @Test
    void geocodeProxiesPlaceSearch() throws Exception {
        Cookie user = loginAsUser();
        when(geocodingClient.search("krak")).thenReturn(List.of(
                new Place("Kraków", "Lesser Poland", "Poland", 50.06, 19.94, "Europe/Warsaw")));

        mvc.perform(get("/api/geocode").cookie(user).param("q", "krak"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].timezone", is("Europe/Warsaw")));
        mvc.perform(get("/api/geocode").cookie(user).param("q", "k"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void geocodeFailureBecomesBadGateway() throws Exception {
        Cookie user = loginAsUser();
        when(geocodingClient.search("oslo")).thenThrow(new RestClientException("down"));
        mvc.perform(get("/api/geocode").cookie(user).param("q", "oslo")).andExpect(status().isBadGateway());
    }
}
