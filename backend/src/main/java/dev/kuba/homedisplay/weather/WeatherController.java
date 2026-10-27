package dev.kuba.homedisplay.weather;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WeatherController {

    private final WeatherService weather;

    public WeatherController(WeatherService weather) {
        this.weather = weather;
    }

    @GetMapping("/api/weather")
    public WeatherService.Snapshot get() {
        return weather.snapshot();
    }
}
