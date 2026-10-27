package dev.kuba.homedisplay.settings;

/** A geocoding search candidate. */
public record Place(String name, String region, String country, double latitude, double longitude, String timezone) {
}
