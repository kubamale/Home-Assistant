package dev.kuba.homedisplay.devices.agent;

import java.time.Clock;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import dev.kuba.homedisplay.devices.ChannelChange;
import dev.kuba.homedisplay.devices.Device;
import dev.kuba.homedisplay.devices.DeviceAdapter;
import dev.kuba.homedisplay.devices.DeviceException;
import dev.kuba.homedisplay.stream.ChangePublisher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** {@link DeviceAdapter} whose devices live behind the Pi home agent. */
@Component
@ConditionalOnProperty(name = "app.devices.mode", havingValue = "agent")
public class RemoteAgentAdapter implements DeviceAdapter {

    private final AgentRegistry registry;
    private final AgentGateway gateway;
    private final ChangePublisher changes;
    private final JsonMapper json;
    private final Clock clock;

    public RemoteAgentAdapter(AgentRegistry registry, AgentGateway gateway, ChangePublisher changes, JsonMapper json,
            Clock clock) {
        this.registry = registry;
        this.gateway = gateway;
        this.changes = changes;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public List<Device> devices() {
        return registry.list();
    }

    @Override
    public boolean online() {
        return gateway.connected();
    }

    @Override
    public Device setPower(String id, boolean on) {
        Device device = connectedDevice(id);
        JsonNode result = gateway.sendCommand(id, "setPower", json.createObjectNode().put("on", on));
        if (!applyResult(result)) {
            registry.applyPower(id, on, clock.instant());
        }
        changes.publish(ChangePublisher.DEVICES);
        return registry.find(id).orElse(device);
    }

    @Override
    public Device setChannel(String id, String channel, ChannelChange change) {
        Device device = connectedDevice(id);
        ObjectNode args = json.createObjectNode();
        if (change.on() != null) {
            args.put("on", change.on());
        }
        if (change.brightness() != null) {
            args.put("brightness", change.brightness());
        }
        if (change.colorTemp() != null) {
            args.put("colorTemp", change.colorTemp());
        }
        if (change.hue() != null) {
            args.put("hue", change.hue());
            args.put("saturation", change.saturation());
        }
        JsonNode result = gateway.sendCommand(id, "set", channel, args);
        if (!applyResult(result)) {
            registry.applyChange(id, channel, change, clock.instant());
        }
        changes.publish(ChangePublisher.DEVICES);
        return registry.find(id).orElse(device);
    }

    private Device connectedDevice(String id) {
        Device device = registry.find(id).orElseThrow(() -> DeviceException.notFound(id));
        if (!gateway.connected()) {
            throw DeviceException.offline();
        }
        return device;
    }

    /** Takes the device state the agent read back after the command; false when the result carried none. */
    private boolean applyResult(JsonNode result) {
        JsonNode state = result.path("state");
        if (!state.isObject() || state.path("deviceId").asString("").isEmpty()) {
            return false;
        }
        gateway.applyState(state);
        return true;
    }
}
