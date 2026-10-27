package dev.kuba.homedisplay.weather;

import dev.kuba.homedisplay.settings.Location;

public interface WeatherClient {

    WeatherData fetch(Location location);
}
