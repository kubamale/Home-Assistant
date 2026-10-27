package dev.kuba.homedisplay.devices;

/** One independently controllable light of a device, for example "main" or "background". */
public record ChannelInfo(String id, String label, Capabilities capabilities) {

    public static final String MAIN = "main";

    /** What a device without declared channels is treated as: a single light that only switches. */
    public static ChannelInfo main() {
        return new ChannelInfo(MAIN, "Main", Capabilities.powerOnly());
    }
}
