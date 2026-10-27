package dev.kuba.homedisplay.weather;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import dev.kuba.homedisplay.config.AppProperties;
import dev.kuba.homedisplay.settings.Location;
import tools.jackson.databind.JsonNode;

@Component
public class OpenMeteoClient implements WeatherClient {

    private final RestClient http;
    private final String url;

    public OpenMeteoClient(RestClient externalRestClient, AppProperties props) {
        this.http = externalRestClient;
        this.url = props.weather().forecastUrl();
    }

    @Override
    public WeatherData fetch(Location location) {
        JsonNode body = http.get()
                .uri(UriComponentsBuilder.fromUriString(url)
                        .queryParam("latitude", location.latitude())
                        .queryParam("longitude", location.longitude())
                        .queryParam("timezone", location.timezone())
                        .queryParam("forecast_days", 1)
                        .queryParam("current", "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,"
                                + "precipitation,is_day")
                        .queryParam("daily", "weather_code,temperature_2m_max,temperature_2m_min,"
                                + "precipitation_probability_max,sunrise,sunset")
                        .queryParam("hourly", "temperature_2m,precipitation_probability,weather_code")
                        .build().encode().toUri())
                .retrieve()
                .body(JsonNode.class);
        return parse(location, body);
    }

    static WeatherData parse(Location location, JsonNode body) {
        if (body == null || !body.has("current") || !body.has("daily")) {
            throw new IllegalStateException("Unexpected weather response");
        }
        JsonNode c = body.get("current");
        Condition now = Condition.fromWmo(c.path("weather_code").asInt(-1));
        WeatherData.Current current = new WeatherData.Current(
                c.path("temperature_2m").asDouble(),
                c.path("apparent_temperature").asDouble(),
                now.key(),
                now.label(),
                c.path("wind_speed_10m").asDouble(),
                c.path("precipitation").asDouble(),
                c.path("is_day").asInt() == 1);

        JsonNode d = body.get("daily");
        Condition day = Condition.fromWmo(d.path("weather_code").path(0).asInt(-1));
        WeatherData.Today today = new WeatherData.Today(
                d.path("temperature_2m_min").path(0).asDouble(),
                d.path("temperature_2m_max").path(0).asDouble(),
                d.path("precipitation_probability_max").path(0).isNumber()
                        ? d.path("precipitation_probability_max").path(0).asInt() : null,
                day.key(),
                day.label(),
                d.path("sunrise").path(0).asString(null),
                d.path("sunset").path(0).asString(null));

        List<WeatherData.Hour> hours = new ArrayList<>();
        JsonNode h = body.path("hourly");
        JsonNode times = h.path("time");
        for (int i = 0; i < times.size(); i++) {
            JsonNode probability = h.path("precipitation_probability").path(i);
            hours.add(new WeatherData.Hour(
                    times.get(i).asString(),
                    h.path("temperature_2m").path(i).asDouble(),
                    probability.isNumber() ? probability.asInt() : null,
                    Condition.fromWmo(h.path("weather_code").path(i).asInt(-1)).key()));
        }
        return new WeatherData(location.name(), current, today, hours);
    }
}
