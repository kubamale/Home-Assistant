package dev.kuba.homedisplay.devices;

import java.time.Clock;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import dev.kuba.homedisplay.stream.ChangePublisher;

/** Keeps the stored devices in step with what the agent reports. */
@Component
public class DeviceInventory {

    private final DeviceRepository devices;
    private final ChangePublisher changes;
    private final Clock clock;

    public DeviceInventory(DeviceRepository devices, ChangePublisher changes, Clock clock) {
        this.devices = devices;
        this.changes = changes;
        this.clock = clock;
    }

    /** Adds new devices (unassigned), refreshes known ones and announces the change only when something differs. */
    @EventListener
    @Transactional
    public void onDevicesDiscovered(DevicesDiscoveredEvent event) {
        boolean changed = false;
        for (DiscoveredDevice device : event.devices()) {
            StoredDevice existing = devices.findById(device.id()).orElse(null);
            if (existing == null || !existing.reportedName().equals(device.name())
                    || !existing.type().equals(device.type()) || !existing.channels().equals(device.channels())) {
                changed = true;
            }
            devices.upsert(device, clock.instant());
        }
        if (changed) {
            changes.publish(ChangePublisher.HOME);
        }
    }
}
