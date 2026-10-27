package dev.kuba.homedisplay.devices;

import java.util.List;

/** A device as the agent lists it: enough to add it to the stored inventory. */
public record DiscoveredDevice(String id, String name, String type, List<ChannelInfo> channels) {

    public DiscoveredDevice {
        channels = channels == null || channels.isEmpty() ? List.of(ChannelInfo.main()) : channels;
    }

    public DiscoveredDevice(String id, String name, String type) {
        this(id, name, type, null);
    }
}
