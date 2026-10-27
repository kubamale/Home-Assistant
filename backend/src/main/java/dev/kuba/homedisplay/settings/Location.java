package dev.kuba.homedisplay.settings;

/** The home location chosen in the app. The timezone also defines "today" for the whole system. */
public record Location(String name, double latitude, double longitude, String timezone) {
}
