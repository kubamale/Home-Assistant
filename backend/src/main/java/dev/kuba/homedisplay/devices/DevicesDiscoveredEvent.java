package dev.kuba.homedisplay.devices;

import java.util.List;

/** Published whenever the set of devices is reported (agent hello or device list, fake adapter at startup). */
public record DevicesDiscoveredEvent(List<DiscoveredDevice> devices) {

    public static DevicesDiscoveredEvent from(List<Device> devices) {
        return new DevicesDiscoveredEvent(
                devices.stream().map(d -> new DiscoveredDevice(d.id(), d.name(), d.type(), d.channels())).toList());
    }
}
