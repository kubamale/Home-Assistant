package dev.kuba.homedisplay.settings;

/** Published after a new location is saved so weather and calendar can refresh themselves. */
public record LocationChangedEvent(Location location) {
}
