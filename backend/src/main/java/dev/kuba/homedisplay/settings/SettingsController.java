package dev.kuba.homedisplay.settings;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
public class SettingsController {

    private final SettingsService settings;
    private final GeocodingClient geocoding;

    public SettingsController(SettingsService settings, GeocodingClient geocoding) {
        this.settings = settings;
        this.geocoding = geocoding;
    }

    public record SettingsResponse(Location location) {
    }

    public record LocationRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotBlank @Size(max = 64) String timezone) {
    }

    @GetMapping("/settings")
    public SettingsResponse get() {
        return new SettingsResponse(settings.location().orElse(null));
    }

    @PutMapping("/settings/location")
    public SettingsResponse putLocation(@Valid @RequestBody LocationRequest request) {
        Location saved = settings.saveLocation(new Location(
                request.name().trim(), request.latitude(), request.longitude(), request.timezone()));
        return new SettingsResponse(saved);
    }

    @GetMapping("/geocode")
    public List<Place> geocode(@RequestParam("q") String query) {
        if (query.trim().length() < 2) {
            return List.of();
        }
        try {
            return geocoding.search(query.trim());
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Place search is unavailable right now");
        }
    }
}
