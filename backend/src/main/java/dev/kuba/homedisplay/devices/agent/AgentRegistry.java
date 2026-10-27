package dev.kuba.homedisplay.devices.agent;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import dev.kuba.homedisplay.devices.ChannelChange;
import dev.kuba.homedisplay.devices.ChannelInfo;
import dev.kuba.homedisplay.devices.ChannelState;
import dev.kuba.homedisplay.devices.Device;

/**
 * What the Pi agent last told us about its devices. Kept when the agent disconnects so the app can
 * still show the last known state (flagged unavailable through {@link #online()}).
 */
@Component
@ConditionalOnProperty(name = "app.devices.mode", havingValue = "agent")
public class AgentRegistry {

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private volatile boolean online;

    public boolean online() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public List<Device> list() {
        return devices.values().stream().sorted(Comparator.comparing(Device::name)).toList();
    }

    public Optional<Device> find(String id) {
        return Optional.ofNullable(devices.get(id));
    }

    /** Replaces the device set, keeping known state for devices and channels that are still present. */
    public void replaceDevices(List<DeviceInfo> infos, Instant now) {
        Map<String, Device> next = new ConcurrentHashMap<>();
        for (DeviceInfo info : infos) {
            Device previous = devices.get(info.id());
            boolean reachable = previous != null && previous.reachable();
            Instant updated = previous != null ? previous.updatedAt() : now;
            List<ChannelState> states = info.channels().stream().map(channel -> previous == null ? ChannelState.of(channel.id(), false)
                    : previous.states().stream().filter(st -> st.id().equals(channel.id())).findFirst()
                            .orElse(ChannelState.of(channel.id(), false))).toList();
            next.put(info.id(), new Device(info.id(), info.name(), info.type(), states.stream().anyMatch(ChannelState::on),
                    reachable, updated, info.channels(), states));
        }
        devices.keySet().retainAll(next.keySet());
        devices.putAll(next);
    }

    /**
     * Applies a state report. {@code channels} may be empty for an agent that does not report them; then
     * a device with a single channel takes {@code power} as that channel's state. Reports for unknown
     * devices are ignored.
     */
    public boolean updateState(String id, boolean power, boolean reachable, List<ChannelState> channels, Instant at) {
        Device current = devices.get(id);
        if (current == null) {
            return false;
        }
        List<ChannelState> states = channels.isEmpty()
                ? current.channels().size() == 1 ? List.of(ChannelState.of(current.channels().get(0).id(), power)) : current.states()
                : mergeStates(current, channels);
        boolean anyOn = channels.isEmpty() && current.channels().size() != 1 ? power : states.stream().anyMatch(ChannelState::on);
        Device updated = new Device(id, current.name(), current.type(), anyOn, reachable, at, current.channels(), states);
        devices.put(id, updated);
        return !updated.equals(current);
    }

    /** The reported channels in the device's channel order; channels the report left out keep their state. */
    private static List<ChannelState> mergeStates(Device current, List<ChannelState> reported) {
        return current.channels().stream().map(channel -> reported.stream().filter(st -> st.id().equals(channel.id()))
                .findFirst()
                .orElseGet(() -> current.states().stream().filter(st -> st.id().equals(channel.id())).findFirst()
                        .orElse(ChannelState.of(channel.id(), false)))).toList();
    }

    /** Every channel on or off, after a device-wide power command. */
    public void applyPower(String id, boolean on, Instant at) {
        devices.computeIfPresent(id, (key, device) -> device.withPower(on, at).withReachable(true));
    }

    /** One channel changed, after a command whose result carried no state. */
    public void applyChange(String id, String channel, ChannelChange change, Instant at) {
        devices.computeIfPresent(id, (key, device) -> device.states().stream().filter(st -> st.id().equals(channel))
                .findFirst().map(st -> device.withChannel(st.with(change), at).withReachable(true)).orElse(device));
    }

    public record DeviceInfo(String id, String name, String type, List<ChannelInfo> channels) {

        public DeviceInfo {
            channels = channels == null || channels.isEmpty() ? List.of(ChannelInfo.main()) : channels;
        }
    }
}
