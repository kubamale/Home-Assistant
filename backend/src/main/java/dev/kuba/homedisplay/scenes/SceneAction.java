package dev.kuba.homedisplay.scenes;

import dev.kuba.homedisplay.devices.ChannelChange;

/** What a scene does to one light: the settings to apply; a null setting is left alone. */
public record SceneAction(String deviceId, String channel, ChannelChange settings) {
}
