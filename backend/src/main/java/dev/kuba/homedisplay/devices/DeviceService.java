package dev.kuba.homedisplay.devices;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import dev.kuba.homedisplay.rooms.RoomAccess;
import dev.kuba.homedisplay.rooms.RoomRepository;
import dev.kuba.homedisplay.scenes.SceneRepository;
import dev.kuba.homedisplay.stream.ChangePublisher;
import dev.kuba.homedisplay.users.Viewer;

@Service
public class DeviceService {

    public static final int QUICK_ACCESS_SLOTS = 4;

    /** What the API returns: the devices plus whether the thing that owns them is reachable. */
    public record Snapshot(List<HomeDevice> devices, boolean agentOnline) {
    }

    private final DeviceAdapter adapter;
    private final DeviceRepository repository;
    private final RoomRepository rooms;
    private final RoomAccess roomAccess;
    private final SceneRepository scenes;
    private final ChangePublisher changes;

    public DeviceService(DeviceAdapter adapter, DeviceRepository repository, RoomRepository rooms,
            RoomAccess roomAccess, SceneRepository scenes, ChangePublisher changes) {
        this.adapter = adapter;
        this.repository = repository;
        this.rooms = rooms;
        this.roomAccess = roomAccess;
        this.scenes = scenes;
        this.changes = changes;
    }

    /**
     * Every device without anyone's pins and without any access rule. Only for code that applies the rules itself
     * (scenes check each action); anything that shows devices to a person uses {@link #snapshot(Viewer)}.
     */
    public Snapshot snapshot() {
        return build(null);
    }

    /**
     * The devices this person may see, with their pins and whether they may control each one (a room can be view only
     * for them). A device in a private room they are not in is not listed at all.
     */
    @Transactional(readOnly = true)
    public Snapshot snapshot(Viewer viewer) {
        RoomAccess.For access = roomAccess.of(viewer);
        Snapshot all = build(viewer.userId());
        List<HomeDevice> visible = all.devices().stream().filter(d -> access.canSee(d.roomId()))
                .map(d -> d.withControllable(access.canControl(d.roomId()))).toList();
        return new Snapshot(visible, all.agentOnline());
    }

    /** What this person may do with one device: it does not exist for them if they cannot see it, 403 if they cannot control it. */
    public void requireControl(Viewer viewer, String deviceId) {
        StoredDevice device = repository.findById(deviceId).orElseThrow(() -> DeviceException.notFound(deviceId));
        RoomAccess.For access = roomAccess.of(viewer);
        if (!access.canSee(device.roomId())) {
            throw DeviceException.notFound(deviceId);
        }
        if (!access.canControl(device.roomId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only look at the devices in this room");
        }
    }

    /** Stored devices merged with live state, with the pins of this person ({@code null}: none). */
    private Snapshot build(Long userId) {
        Map<String, Device> live = adapter.devices().stream()
                .collect(Collectors.toMap(Device::id, Function.identity(), (a, b) -> a));
        List<QuickAccessItem> pinned = userId == null ? List.of() : repository.quickAccess(userId);
        List<HomeDevice> devices = repository.findAll().stream()
                .map(stored -> homeDevice(stored, live.get(stored.id()), pinned)).toList();
        return new Snapshot(devices, adapter.online());
    }

    private static HomeDevice homeDevice(StoredDevice stored, Device state, List<QuickAccessItem> pinned) {
        List<HomeChannel> channels = stored.channels().stream().map(info -> {
            ChannelState values = state == null ? null
                    : state.states().stream().filter(st -> st.id().equals(info.id())).findFirst().orElse(null);
            int slot = pinned.indexOf(new QuickAccessItem(stored.id(), info.id()));
            return new HomeChannel(info.id(), info.label(), info.capabilities(), values != null && values.on(),
                    values == null ? null : values.brightness(), values == null ? null : values.colorTemp(),
                    values == null ? null : values.hue(), values == null ? null : values.saturation(),
                    values == null ? null : values.colorMode(), slot < 0 ? null : slot + 1);
        }).toList();
        boolean on = channels.stream().anyMatch(HomeChannel::on);
        return new HomeDevice(stored.id(), stored.name(), stored.reportedName(), stored.type(), stored.roomId(), on,
                state != null && state.reachable(), state != null, state != null ? state.updatedAt() : null,
                stored.lastSeenAt(), channels);
    }

    /** Switches every channel of the device and returns it as the API shows it. */
    public HomeDevice setPower(Viewer viewer, String id, boolean on) {
        requireControl(viewer, id);
        adapter.setPower(id, on);
        return find(viewer, id);
    }

    /** Changes one channel after checking the change against what the channel supports. */
    public HomeDevice setChannel(Viewer viewer, String id, String channelId, ChannelChange change) {
        requireControl(viewer, id);
        StoredDevice device = repository.findById(id).orElseThrow(() -> DeviceException.notFound(id));
        ChannelInfo channel = device.channel(channelId);
        if (channel == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such channel: " + channelId);
        }
        change.validate(channel);
        adapter.setChannel(id, channelId, change);
        return find(viewer, id);
    }

    private HomeDevice find(Viewer viewer, String id) {
        return snapshot(viewer).devices().stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> DeviceException.notFound(id));
    }

    /**
     * Renames and/or moves a device. A null {@code name} keeps it, a blank one goes back to the reported name;
     * {@code clearRoom} unassigns, otherwise a null {@code roomId} keeps the room.
     */
    @Transactional
    public void update(String id, String name, Long roomId, boolean clearRoom) {
        StoredDevice device = repository.findById(id).orElseThrow(() -> DeviceException.notFound(id));
        if (name != null) {
            String trimmed = name.trim();
            repository.setNameOverride(id, trimmed.isEmpty() || trimmed.equals(device.reportedName()) ? null : trimmed);
        }
        if (clearRoom) {
            repository.setRoom(id, null);
        } else if (roomId != null) {
            if (rooms.findById(roomId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such room");
            }
            repository.setRoom(id, roomId);
        }
        changes.publish(ChangePublisher.HOME);
    }

    /** Removes a stored device and its quick access slots. If the agent lists it again it returns unassigned. */
    @Transactional
    public void forget(String id) {
        if (!repository.delete(id)) {
            throw DeviceException.notFound(id);
        }
        repository.compactQuickAccess(); // closing the gaps removed pins leave keeps everyone's slots 1..n
        changes.publish(ChangePublisher.HOME);
    }

    /** Replaces this person's quick access slots with these lights and scenes, in order. */
    @Transactional
    public void setQuickAccess(Viewer viewer, List<QuickAccessItem> items) {
        if (items.size() > QUICK_ACCESS_SLOTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At most " + QUICK_ACCESS_SLOTS + " can be pinned");
        }
        if (new HashSet<>(items).size() != items.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A light can only be pinned once");
        }
        for (QuickAccessItem item : items) {
            if (item.isScene()) {
                if (scenes.findVisible(viewer.userId(), item.sceneId()).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such scene");
                }
                continue;
            }
            requireControl(viewer, item.deviceId()); // a favourite is something you can switch
            StoredDevice device = repository.findById(item.deviceId())
                    .orElseThrow(() -> DeviceException.notFound(item.deviceId()));
            if (device.channel(item.channel()) == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such channel: " + item.channel());
            }
        }
        repository.replaceQuickAccess(viewer.userId(), items);
        changes.publish(ChangePublisher.HOME);
    }
}
