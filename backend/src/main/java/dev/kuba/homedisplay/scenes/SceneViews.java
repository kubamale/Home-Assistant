package dev.kuba.homedisplay.scenes;

import java.util.List;

import dev.kuba.homedisplay.devices.Capabilities;
import dev.kuba.homedisplay.devices.ChannelChange;

/** The shapes the scene API returns. */
public final class SceneViews {

    private SceneViews() {
    }

    /**
     * @param capabilities what the light supports now, null if the channel no longer exists
     * @param settings     the stored settings; null fields are left alone when the scene is applied
     */
    public record ActionView(String deviceId, String deviceName, String channel, String channelLabel,
            Capabilities capabilities, ChannelChange settings) {
    }

    /**
     * @param quickAccess slot 1 to 4 on the viewer's dashboard, or null
     * @param shared      everyone's scene (the admin edits it); otherwise it is the viewer's own
     * @param editable    whether the viewer may change or delete it
     */
    public record SceneView(long id, String name, String kind, Integer quickAccess, boolean shared, boolean editable,
            List<ActionView> actions) {
    }

    public record SceneList(List<SceneView> scenes) {
    }

    public record Failure(String deviceId, String deviceName, String channel, String reason) {
    }

    /** The outcome of applying a scene: how many actions went through and which did not. */
    public record ApplyResult(int applied, List<Failure> failed) {
    }
}
