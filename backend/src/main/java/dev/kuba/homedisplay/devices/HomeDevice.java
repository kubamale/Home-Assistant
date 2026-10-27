package dev.kuba.homedisplay.devices;

import java.time.Instant;
import java.util.List;

/**
 * What the API returns for a device: the stored organisation merged with live state.
 *
 * @param name      the name to show: the user's own if set, otherwise {@code reportedName}
 * @param power     true when any channel is on
 * @param present   whether the agent currently lists the device; false for one that went missing
 * @param updatedAt when the live state last changed, null without live state
 */
public record HomeDevice(String id, String name, String reportedName, String type, Long roomId, boolean power,
        boolean reachable, boolean present, Instant updatedAt, Instant lastSeenAt, List<HomeChannel> channels) {
}
