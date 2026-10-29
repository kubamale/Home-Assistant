package dev.kuba.homedisplay.voice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.kuba.homedisplay.devices.Capabilities;
import dev.kuba.homedisplay.devices.HomeChannel;
import dev.kuba.homedisplay.devices.HomeDevice;
import dev.kuba.homedisplay.rooms.Room;
import dev.kuba.homedisplay.scenes.SceneViews.SceneView;

/**
 * The lights and scenes the assistant can name, frozen when a conversation starts. Each light gets a short
 * reference (l1, l2, ...) and each scene too (s1, ...), so the model never handles the long device ids and
 * cannot invent one. Names are the user's own, so they are data, not instructions.
 */
final class HomeCatalog {

    /** One controllable light: a channel of a device. */
    record Light(String ref, String deviceId, String channel, String name, String room, Long roomId) {
    }

    record SceneRef(String ref, long id, String name) {
    }

    private final Map<String, Light> lights = new LinkedHashMap<>();
    private final Map<String, SceneRef> scenes = new LinkedHashMap<>();
    private final String prompt;

    HomeCatalog(List<HomeDevice> devices, List<Room> rooms, List<SceneView> sceneViews) {
        StringBuilder text = new StringBuilder();
        int next = 1;
        for (Room room : rooms) {
            List<HomeDevice> inRoom = devices.stream().filter(d -> d.present() && Long.valueOf(room.id()).equals(d.roomId())).toList();
            if (!inRoom.isEmpty()) {
                text.append("Room \"").append(room.name()).append("\":\n");
                next = describe(text, inRoom, room, next);
            }
        }
        List<HomeDevice> unassigned = devices.stream()
                .filter(d -> d.present() && rooms.stream().noneMatch(r -> Long.valueOf(r.id()).equals(d.roomId()))).toList();
        if (!unassigned.isEmpty()) {
            text.append("Not in a room:\n");
            describe(text, unassigned, null, next);
        }
        if (lights.isEmpty()) {
            text.append("(no lights are known yet)\n");
        }
        int s = 1;
        text.append("Scenes:\n");
        for (SceneView scene : sceneViews) {
            SceneRef ref = new SceneRef("s" + s++, scene.id(), scene.name());
            scenes.put(ref.ref(), ref);
            text.append("  ").append(ref.ref()).append("  \"").append(scene.name()).append("\"\n");
        }
        this.prompt = text.toString();
    }

    private int describe(StringBuilder text, List<HomeDevice> devices, Room room, int next) {
        for (HomeDevice device : devices) {
            for (HomeChannel channel : device.channels()) {
                String name = device.channels().size() > 1 ? device.name() + " · " + channel.label() : device.name();
                Light light = new Light("l" + next++, device.id(), channel.id(), name, room == null ? null : room.name(),
                        room == null ? null : room.id());
                lights.put(light.ref(), light);
                text.append("  ").append(light.ref()).append("  \"").append(name).append("\"  (")
                        .append(abilities(channel.capabilities())).append(")\n");
            }
        }
        return next;
    }

    private static String abilities(Capabilities caps) {
        StringBuilder out = new StringBuilder("on/off");
        if (caps.brightness()) {
            out.append(", brightness");
        }
        if (caps.colorTemp() != null) {
            out.append(", white ").append(caps.colorTemp().min()).append("-").append(caps.colorTemp().max()).append(" K");
        }
        if (caps.color()) {
            out.append(", colour");
        }
        return out.toString();
    }

    /** The lights and scenes as text for the system prompt. */
    String prompt() {
        return prompt;
    }

    java.util.Collection<Light> lights() {
        return lights.values();
    }

    java.util.Collection<SceneRef> scenes() {
        return scenes.values();
    }

    Optional<Light> light(String ref) {
        return Optional.ofNullable(ref == null ? null : lights.get(ref.trim().toLowerCase()));
    }

    Optional<SceneRef> scene(String ref) {
        return Optional.ofNullable(ref == null ? null : scenes.get(ref.trim().toLowerCase()));
    }

    Optional<Light> lightFor(String deviceId, String channel) {
        return lights.values().stream().filter(l -> l.deviceId().equals(deviceId) && l.channel().equals(channel)).findFirst();
    }
}
