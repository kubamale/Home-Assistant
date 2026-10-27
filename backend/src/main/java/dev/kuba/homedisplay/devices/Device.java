package dev.kuba.homedisplay.devices;

import java.time.Instant;
import java.util.List;

/**
 * A controllable device as the app sees it, independent of the protocol behind it.
 *
 * @param power  true when any channel is on
 * @param states the live state of each channel, in the order of {@code channels}
 */
public record Device(String id, String name, String type, boolean power, boolean reachable, Instant updatedAt,
        List<ChannelInfo> channels, List<ChannelState> states) {

    /** A device with a single light that only switches on and off. */
    public Device(String id, String name, String type, boolean power, boolean reachable, Instant updatedAt) {
        this(id, name, type, power, reachable, updatedAt, List.of(ChannelInfo.main()),
                List.of(ChannelState.of(ChannelInfo.MAIN, power)));
    }

    /** Every channel switched on or off. */
    public Device withPower(boolean newPower, Instant at) {
        return new Device(id, name, type, newPower, reachable, at, channels,
                states.stream().map(s -> s.withOn(newPower)).toList());
    }

    /** One channel's state replaced; the device is on when any channel is. */
    public Device withChannel(ChannelState state, Instant at) {
        List<ChannelState> next = states.stream().map(s -> s.id().equals(state.id()) ? state : s).toList();
        return new Device(id, name, type, next.stream().anyMatch(ChannelState::on), reachable, at, channels, next);
    }

    public Device withReachable(boolean newReachable) {
        return new Device(id, name, type, power, newReachable, updatedAt, channels, states);
    }

    public ChannelInfo channel(String channelId) {
        return channels.stream().filter(c -> c.id().equals(channelId)).findFirst().orElse(null);
    }
}
