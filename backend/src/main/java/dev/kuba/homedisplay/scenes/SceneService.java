package dev.kuba.homedisplay.scenes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import dev.kuba.homedisplay.devices.ChannelChange;
import dev.kuba.homedisplay.devices.DeviceAdapter;
import dev.kuba.homedisplay.devices.DeviceException;
import dev.kuba.homedisplay.devices.DeviceRepository;
import dev.kuba.homedisplay.devices.DeviceService;
import dev.kuba.homedisplay.devices.HomeChannel;
import dev.kuba.homedisplay.devices.HomeDevice;
import dev.kuba.homedisplay.devices.QuickAccessItem;
import dev.kuba.homedisplay.scenes.SceneViews.ActionView;
import dev.kuba.homedisplay.scenes.SceneViews.ApplyResult;
import dev.kuba.homedisplay.scenes.SceneViews.Failure;
import dev.kuba.homedisplay.scenes.SceneViews.SceneView;
import dev.kuba.homedisplay.stream.ChangePublisher;

@Service
public class SceneService {

    /** A light picked for capture. */
    public record LightRef(String deviceId, String channel) {
    }

    private final SceneRepository scenes;
    private final DeviceRepository devices;
    private final DeviceService deviceService;
    private final DeviceAdapter adapter;
    private final ChangePublisher changes;

    public SceneService(SceneRepository scenes, DeviceRepository devices, DeviceService deviceService,
            DeviceAdapter adapter, ChangePublisher changes) {
        this.scenes = scenes;
        this.devices = devices;
        this.deviceService = deviceService;
        this.adapter = adapter;
        this.changes = changes;
    }

    @Transactional(readOnly = true)
    public List<SceneView> list() {
        Map<String, HomeDevice> byId = byId(deviceService.snapshot().devices());
        List<QuickAccessItem> pinned = devices.quickAccess();
        Map<Long, List<SceneAction>> actions = scenes.actionsByScene();
        return scenes.findAll().stream().map(scene -> view(scene, actions.getOrDefault(scene.id(), List.of()), byId, pinned)).toList();
    }

    private SceneView view(Scene scene, List<SceneAction> actions, Map<String, HomeDevice> byId, List<QuickAccessItem> pinned) {
        int slot = pinned.indexOf(QuickAccessItem.scene(scene.id()));
        List<ActionView> views = actions.stream().map(a -> {
            HomeDevice device = byId.get(a.deviceId());
            HomeChannel channel = device == null ? null
                    : device.channels().stream().filter(c -> c.id().equals(a.channel())).findFirst().orElse(null);
            return new ActionView(a.deviceId(), device == null ? a.deviceId() : device.name(), a.channel(),
                    channel == null ? a.channel() : channel.label(), channel == null ? null : channel.capabilities(),
                    a.settings());
        }).toList();
        return new SceneView(scene.id(), scene.name(), scene.kind(), slot < 0 ? null : slot + 1, views);
    }

    @Transactional
    public SceneView create(String name, List<SceneAction> actions) {
        validate(actions);
        rejectDuplicateName(name.trim(), -1);
        Scene scene = scenes.insert(name.trim());
        scenes.replaceActions(scene.id(), actions);
        changes.publish(ChangePublisher.HOME);
        return one(scene.id());
    }

    /** Creates a scene from the current settings of the chosen lights. */
    @Transactional
    public SceneView capture(String name, List<LightRef> lights) {
        if (lights.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick at least one light");
        }
        Map<String, HomeDevice> byId = byId(deviceService.snapshot().devices());
        List<SceneAction> actions = new ArrayList<>();
        for (LightRef light : lights) {
            HomeDevice device = byId.get(light.deviceId());
            if (device == null) {
                throw DeviceException.notFound(light.deviceId());
            }
            HomeChannel channel = device.channels().stream().filter(c -> c.id().equals(light.channel())).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such channel: " + light.channel()));
            actions.add(new SceneAction(light.deviceId(), light.channel(), currentSettings(channel)));
        }
        return create(name, actions);
    }

    /** The settings that reproduce what a light shows now: off, or on with its brightness and white or colour. */
    static ChannelChange currentSettings(HomeChannel channel) {
        if (!channel.on()) {
            return new ChannelChange(false, null, null, null, null);
        }
        Integer brightness = channel.capabilities().brightness() ? channel.brightness() : null;
        boolean showsColor = channel.capabilities().color() && channel.hue() != null && channel.saturation() != null
                && (!"temperature".equals(channel.colorMode()) || channel.colorTemp() == null);
        if (showsColor) {
            return new ChannelChange(true, brightness, null, channel.hue(), channel.saturation());
        }
        Integer temp = channel.capabilities().colorTemp() != null ? channel.colorTemp() : null;
        return new ChannelChange(true, brightness, temp, null, null);
    }

    /** Replaces the name and actions. The built-in All off scene can only be renamed. */
    @Transactional
    public SceneView update(long id, String name, List<SceneAction> actions) {
        Scene scene = scenes.findById(id).orElseThrow(SceneService::notFound);
        rejectDuplicateName(name.trim(), id);
        if (scene.isAllOff()) {
            if (!actions.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The built-in scene has no actions to edit");
            }
        } else {
            validate(actions);
            scenes.replaceActions(id, actions);
        }
        scenes.rename(id, name.trim());
        changes.publish(ChangePublisher.HOME);
        return one(id);
    }

    @Transactional
    public void delete(long id) {
        Scene scene = scenes.findById(id).orElseThrow(SceneService::notFound);
        if (scene.isAllOff()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The built-in scene cannot be deleted");
        }
        scenes.delete(id);
        // Closing the gap a removed pin leaves keeps the slots 1..n.
        devices.replaceQuickAccess(devices.quickAccess());
        changes.publish(ChangePublisher.HOME);
    }

    /** The list must contain every existing scene exactly once. */
    @Transactional
    public void reorder(List<Long> ids) {
        List<Long> existing = scenes.findAll().stream().map(Scene::id).toList();
        if (ids.size() != existing.size() || !new HashSet<>(ids).equals(new HashSet<>(existing))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "List every scene exactly once");
        }
        for (int i = 0; i < ids.size(); i++) {
            scenes.setPosition(ids.get(i), i);
        }
        changes.publish(ChangePublisher.HOME);
    }

    /**
     * Applies a scene: every action becomes a channel command. Actions for different devices run in
     * parallel, the actions of one device in order. A failed action is reported, the rest carry on.
     */
    public ApplyResult apply(long id) {
        Scene scene = scenes.findById(id).orElseThrow(SceneService::notFound);
        if (!adapter.online()) {
            throw DeviceException.offline();
        }
        Map<String, HomeDevice> byId = byId(deviceService.snapshot().devices());
        return run(scene.isAllOff() ? allOff(byId) : scenes.actions(id), byId);
    }

    /**
     * Applies loose actions the way a scene would (the voice assistant uses this to set several lights at
     * once): in parallel across devices, in order within one, with every failure reported.
     */
    public ApplyResult applyActions(List<SceneAction> actions) {
        if (!adapter.online()) {
            throw DeviceException.offline();
        }
        return run(actions, byId(deviceService.snapshot().devices()));
    }

    private ApplyResult run(List<SceneAction> actions, Map<String, HomeDevice> byId) {
        Map<String, List<SceneAction>> perDevice = new LinkedHashMap<>();
        for (SceneAction action : actions) {
            perDevice.computeIfAbsent(action.deviceId(), k -> new ArrayList<>()).add(action);
        }
        List<Failure> failed = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Failure>>> runs = new ArrayList<>();
            for (List<SceneAction> group : perDevice.values()) {
                runs.add(pool.submit(() -> runGroup(group, byId.get(group.get(0).deviceId()))));
            }
            for (Future<List<Failure>> run : runs) {
                try {
                    failed.addAll(run.get());
                } catch (Exception e) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Applying the scene was interrupted");
                }
            }
        }
        return new ApplyResult(actions.size() - failed.size(), failed);
    }

    /** Switches every channel of every present, reachable device off. */
    private static List<SceneAction> allOff(Map<String, HomeDevice> byId) {
        List<SceneAction> out = new ArrayList<>();
        for (HomeDevice device : byId.values()) {
            if (!device.present() || !device.reachable()) {
                continue;
            }
            for (HomeChannel channel : device.channels()) {
                out.add(new SceneAction(device.id(), channel.id(), new ChannelChange(false, null, null, null, null)));
            }
        }
        return out;
    }

    private List<Failure> runGroup(List<SceneAction> group, HomeDevice device) {
        List<Failure> failures = new ArrayList<>();
        for (SceneAction action : group) {
            String name = device == null ? action.deviceId() : device.name();
            if (device == null || !device.present() || !device.reachable()) {
                failures.add(new Failure(action.deviceId(), name, action.channel(), "Unavailable"));
                continue;
            }
            HomeChannel channel = device.channels().stream().filter(c -> c.id().equals(action.channel())).findFirst().orElse(null);
            if (channel == null) {
                failures.add(new Failure(action.deviceId(), name, action.channel(), "The light no longer exists"));
                continue;
            }
            try {
                ChannelChange change = effective(action.settings());
                change.validate(new dev.kuba.homedisplay.devices.ChannelInfo(channel.id(), channel.label(), channel.capabilities()));
                adapter.setChannel(action.deviceId(), action.channel(), change);
            } catch (ResponseStatusException e) {
                failures.add(new Failure(action.deviceId(), name, action.channel(), e.getReason() == null ? "Failed" : e.getReason()));
            } catch (RuntimeException e) {
                failures.add(new Failure(action.deviceId(), name, action.channel(), "Failed"));
            }
        }
        return failures;
    }

    /** Setting brightness, white or colour implies on; off drops the other settings. */
    static ChannelChange effective(ChannelChange settings) {
        if (Boolean.FALSE.equals(settings.on())) {
            return new ChannelChange(false, null, null, null, null);
        }
        boolean adjusts = settings.brightness() != null || settings.colorTemp() != null || settings.hue() != null;
        if (settings.on() == null && adjusts) {
            return new ChannelChange(true, settings.brightness(), settings.colorTemp(), settings.hue(), settings.saturation());
        }
        return settings;
    }

    private void validate(List<SceneAction> actions) {
        Set<String> seen = new HashSet<>();
        for (SceneAction action : actions) {
            var device = devices.findById(action.deviceId()).orElseThrow(() -> DeviceException.notFound(action.deviceId()));
            var channel = device.channel(action.channel());
            if (channel == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such channel: " + action.channel());
            }
            if (action.settings() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every light needs at least one setting");
            }
            action.settings().validate(channel);
            if (!seen.add(action.deviceId() + "/" + action.channel())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A light can only be in a scene once");
            }
        }
    }

    private SceneView one(long id) {
        return list().stream().filter(s -> s.id() == id).findFirst().orElseThrow(SceneService::notFound);
    }

    private void rejectDuplicateName(String name, long exceptId) {
        if (scenes.nameTaken(name, exceptId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A scene with that name already exists");
        }
    }

    private static Map<String, HomeDevice> byId(List<HomeDevice> devices) {
        Map<String, HomeDevice> out = new LinkedHashMap<>();
        devices.forEach(d -> out.put(d.id(), d));
        return out;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such scene");
    }
}
