package dev.kuba.homedisplay.devices;

/**
 * A channel as the API returns it: what it supports and its live values (null where unsupported or unknown).
 *
 * @param quickAccess slot 1 to 4 on the dashboard, or null
 */
public record HomeChannel(String id, String label, Capabilities capabilities, boolean on, Integer brightness,
        Integer colorTemp, Integer hue, Integer saturation, String colorMode, Integer quickAccess) {
}
