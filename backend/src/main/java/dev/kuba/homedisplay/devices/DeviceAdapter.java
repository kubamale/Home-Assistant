package dev.kuba.homedisplay.devices;

import java.util.List;

/**
 * Where devices come from. The backend only knows devices and power; protocols live elsewhere
 * (in the Pi agent's drivers).
 */
public interface DeviceAdapter {

    /** Devices as last reported, including their last known state. */
    List<Device> devices();

    /** False when the component that owns the devices cannot be reached right now. */
    boolean online();

    /**
     * Switches every channel of the device and returns its new state.
     *
     * @throws DeviceException if the device is unknown, the agent is offline or the command fails
     */
    Device setPower(String id, boolean on);

    /**
     * Applies a change to one channel and returns the device's new state. The change has already been
     * checked against the channel's capabilities.
     *
     * @throws DeviceException if the device is unknown, the agent is offline or the command fails
     */
    Device setChannel(String id, String channel, ChannelChange change);
}
