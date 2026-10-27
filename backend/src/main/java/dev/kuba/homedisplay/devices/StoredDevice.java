package dev.kuba.homedisplay.devices;

import java.time.Instant;
import java.util.List;

/** A device row: what exists and how the user organised it. No live state. */
public record StoredDevice(String id, String type, String reportedName, String nameOverride, Long roomId,
        List<ChannelInfo> channels, Instant firstSeenAt, Instant lastSeenAt) {

    public String name() {
        return nameOverride != null ? nameOverride : reportedName;
    }

    public ChannelInfo channel(String channelId) {
        return channels.stream().filter(c -> c.id().equals(channelId)).findFirst().orElse(null);
    }
}
