package dev.kuba.homedisplay.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.kuba.homedisplay.settings.Location;
import tools.jackson.databind.json.JsonMapper;

class OpenMeteoParsingTest {

    private static final String RESPONSE = """
            {
              "latitude": 50.06, "longitude": 19.94, "timezone": "Europe/Warsaw",
              "current": {"time": "2026-10-24T14:00", "temperature_2m": 12.4, "apparent_temperature": 10.1,
                          "weather_code": 61, "wind_speed_10m": 17.3, "precipitation": 0.4, "is_day": 1},
              "daily": {"time": ["2026-10-24"], "weather_code": [63], "temperature_2m_max": [14.2],
                        "temperature_2m_min": [7.9], "precipitation_probability_max": [85],
                        "sunrise": ["2026-10-24T06:51"], "sunset": ["2026-10-24T16:29"]},
              "hourly": {"time": ["2026-10-24T00:00", "2026-10-24T01:00"],
                         "temperature_2m": [8.1, 7.9], "precipitation_probability": [10, null],
                         "weather_code": [3, 80]}
            }""";

    @Test
    void parsesCurrentDailyAndHourly() {
        var json = JsonMapper.builder().build().readTree(RESPONSE);
        WeatherData data = OpenMeteoClient.parse(new Location("Kraków", 50.06, 19.94, "Europe/Warsaw"), json);

        assertEquals("Kraków", data.locationName());
        assertEquals(12.4, data.current().temperature());
        assertEquals("rain", data.current().condition());
        assertTrue(data.current().isDay());
        assertEquals(7.9, data.today().minTemperature());
        assertEquals(14.2, data.today().maxTemperature());
        assertEquals(85, data.today().precipitationProbabilityMax());
        assertEquals("2026-10-24T06:51", data.today().sunrise());
        assertEquals(2, data.hourly().size());
        assertEquals("showers", data.hourly().get(1).condition());
        assertNull(data.hourly().get(1).precipitationProbability());
    }

    @Test
    void mapsWmoCodes() {
        assertEquals(Condition.CLEAR, Condition.fromWmo(0));
        assertEquals(Condition.FOG, Condition.fromWmo(45));
        assertEquals(Condition.SNOW, Condition.fromWmo(75));
        assertEquals(Condition.THUNDERSTORM, Condition.fromWmo(96));
        assertEquals(Condition.UNKNOWN, Condition.fromWmo(1234));
    }

    @Test
    void rejectsUnexpectedResponses() {
        var json = JsonMapper.builder().build().readTree("{\"error\":true,\"reason\":\"bad\"}");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> OpenMeteoClient.parse(new Location("X", 0, 0, "UTC"), json));
    }
}
