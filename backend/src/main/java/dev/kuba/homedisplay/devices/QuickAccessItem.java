package dev.kuba.homedisplay.devices;

/** One quick access slot: a light (a channel of a device) or a scene. Exactly one of the two. */
public record QuickAccessItem(String deviceId, String channel, Long sceneId) {

    /** A light. */
    public QuickAccessItem(String deviceId, String channel) {
        this(deviceId, channel, null);
    }

    public static QuickAccessItem scene(long sceneId) {
        return new QuickAccessItem(null, null, sceneId);
    }

    public boolean isScene() {
        return sceneId != null;
    }
}
