package dev.kuba.homedisplay.devices;

/**
 * What a channel supports besides power (which every channel has).
 *
 * @param brightness whether brightness 1 to 100 can be set
 * @param colorTemp  the colour temperature range in Kelvin, null when the channel has no adjustable white
 * @param color      whether hue 0 to 359 and saturation 0 to 100 can be set
 */
public record Capabilities(boolean brightness, Range colorTemp, boolean color) {

    public static Capabilities powerOnly() {
        return new Capabilities(false, null, false);
    }
}
