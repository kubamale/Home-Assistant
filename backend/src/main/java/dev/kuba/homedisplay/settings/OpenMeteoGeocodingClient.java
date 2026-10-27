package dev.kuba.homedisplay.settings;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import dev.kuba.homedisplay.config.AppProperties;
import tools.jackson.databind.JsonNode;

@Component
public class OpenMeteoGeocodingClient implements GeocodingClient {

    private final RestClient http;
    private final String url;

    public OpenMeteoGeocodingClient(RestClient externalRestClient, AppProperties props) {
        this.http = externalRestClient;
        this.url = props.weather().geocodingUrl();
    }

    @Override
    public List<Place> search(String query) {
        JsonNode body = http.get()
                .uri(UriComponentsBuilder.fromUriString(url)
                        .queryParam("name", query)
                        .queryParam("count", 8)
                        .queryParam("language", "en")
                        .queryParam("format", "json")
                        .build().encode().toUri())
                .retrieve()
                .body(JsonNode.class);
        List<Place> places = new ArrayList<>();
        if (body != null && body.has("results")) {
            for (JsonNode r : body.get("results")) {
                if (!r.hasNonNull("timezone")) {
                    continue; // without a timezone the place cannot define "today"
                }
                places.add(new Place(
                        r.path("name").asString(""),
                        r.path("admin1").asString(null),
                        r.path("country").asString(null),
                        r.path("latitude").asDouble(),
                        r.path("longitude").asDouble(),
                        r.path("timezone").asString()));
            }
        }
        return places;
    }
}
