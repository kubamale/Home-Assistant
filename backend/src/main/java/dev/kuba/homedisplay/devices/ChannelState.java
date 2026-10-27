package dev.kuba.homedisplay.devices;

/** The state of one channel. Values the channel does not support are null. */
public record ChannelState(String id, boolean on, Integer brightness, Integer colorTemp, Integer hue,
        Integer saturation, String colorMode) {

    public static ChannelState of(String id, boolean on) {
        return new ChannelState(id, on, null, null, null, null, null);
    }

    public ChannelState withOn(boolean newOn) {
        return new ChannelState(id, newOn, brightness, colorTemp, hue, saturation, colorMode);
    }

    /** This state with a change applied, as the device would be after carrying it out. */
    public ChannelState with(ChannelChange change) {
        boolean newOn = change.on() != null ? change.on() : on;
        Integer newBrightness = change.brightness() != null ? change.brightness() : brightness;
        Integer newTemp = colorTemp;
        Integer newHue = hue;
        Integer newSaturation = saturation;
        String mode = colorMode;
        if (change.colorTemp() != null) {
            newTemp = change.colorTemp();
            mode = "temperature";
        }
        if (change.hue() != null && change.saturation() != null) {
            newHue = change.hue();
            newSaturation = change.saturation();
            mode = "color";
        }
        return new ChannelState(id, newOn, newBrightness, newTemp, newHue, newSaturation, mode);
    }
}
