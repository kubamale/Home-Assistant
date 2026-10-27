package dev.kuba.homedisplay.devices;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.kuba.homedisplay.stream.ChangePublisher;

/** Two simulated lights so the app can be built and tried without the Pi or real bulbs. */
@Component
@ConditionalOnProperty(name = "app.devices.mode", havingValue = "fake", matchIfMissing = true)
public class FakeDeviceAdapter implements DeviceAdapter {

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Clock clock;
    private final ChangePublisher changes;
    private final ApplicationEventPublisher events;

    public FakeDeviceAdapter(Clock clock, ChangePublisher changes, ApplicationEventPublisher events) {
        this.clock = clock;
        this.changes = changes;
        this.events = events;
        seed();
    }

    /** Puts the simulated lights back to their starting state. Tests use this between runs. */
    public void reset() {
        devices.clear();
        seed();
    }

    private void seed() {
        // A two-channel light bar like the Yeelight monitor bar: white main light, colour background light.
        add(new Device("fake-light-bar", "Light bar (fake)", "fake", true, true, clock.instant(),
                List.of(new ChannelInfo("main", "Main", new Capabilities(true, new Range(2700, 6500), false)),
                        new ChannelInfo("background", "Background", new Capabilities(true, new Range(1700, 6500), true))),
                List.of(new ChannelState("main", true, 80, 4000, null, null, "temperature"),
                        new ChannelState("background", false, 50, null, 200, 80, "color"))));
        // A plain dimmable bulb.
        add(new Device("fake-lamp", "Desk lamp (fake)", "fake", false, true, clock.instant(),
                List.of(new ChannelInfo("main", "Main", new Capabilities(true, null, false))),
                List.of(new ChannelState("main", false, 100, null, null, null, null))));
    }

    /** Lists the simulated lights in the stored inventory, the way the agent does on connect. */
    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        events.publishEvent(DevicesDiscoveredEvent.from(devices()));
    }

    private void add(Device device) {
        devices.put(device.id(), device);
    }

    @Override
    public List<Device> devices() {
        return devices.values().stream().sorted(java.util.Comparator.comparing(Device::name)).toList();
    }

    @Override
    public boolean online() {
        return true;
    }

    @Override
    public Device setPower(String id, boolean on) {
        Device updated = devices.computeIfPresent(id, (key, device) -> device.withPower(on, clock.instant()));
        if (updated == null) {
            throw DeviceException.notFound(id);
        }
        changes.publish(ChangePublisher.DEVICES);
        return updated;
    }

    @Override
    public Device setChannel(String id, String channel, ChannelChange change) {
        Device updated = devices.computeIfPresent(id, (key, device) -> {
            ChannelState current = device.states().stream().filter(st -> st.id().equals(channel)).findFirst()
                    .orElseThrow(() -> DeviceException.notFound(id + "/" + channel));
            return device.withChannel(current.with(change), clock.instant());
        });
        if (updated == null) {
            throw DeviceException.notFound(id);
        }
        changes.publish(ChangePublisher.DEVICES);
        return updated;
    }
}
