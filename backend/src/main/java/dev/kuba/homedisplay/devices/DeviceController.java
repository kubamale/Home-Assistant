package dev.kuba.homedisplay.devices;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
public class DeviceController {

    private final DeviceService devices;

    public DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    public record PowerRequest(@NotNull Boolean on) {
    }

    /** All fields are optional. A blank {@code name} clears the user's own name; {@code clearRoom} unassigns. */
    public record UpdateDeviceRequest(@Size(max = 60) String name, Long roomId, Boolean clearRoom) {
    }

    /** A light ({@code deviceId} and {@code channel}) or a scene ({@code sceneId}), not both. */
    public record PinRequest(String deviceId, String channel, Long sceneId) {

        QuickAccessItem toItem() {
            boolean light = deviceId != null && !deviceId.isBlank() && channel != null && !channel.isBlank();
            if (light == (sceneId != null)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Pin either a light or a scene");
            }
            return light ? new QuickAccessItem(deviceId, channel) : QuickAccessItem.scene(sceneId);
        }
    }

    public record QuickAccessRequest(@NotNull List<@Valid @NotNull PinRequest> items) {
    }

    @GetMapping("/devices")
    public DeviceService.Snapshot list() {
        return devices.snapshot();
    }

    @PostMapping("/devices/{id}/power")
    public HomeDevice setPower(@PathVariable String id, @Valid @RequestBody PowerRequest request) {
        return devices.setPower(id, request.on());
    }

    /** Changes one channel: power, brightness, colour temperature or colour. */
    @PostMapping("/devices/{id}/channels/{channel}")
    public HomeDevice setChannel(@PathVariable String id, @PathVariable String channel,
            @RequestBody ChannelChange change) {
        return devices.setChannel(id, channel, change);
    }

    @PatchMapping("/devices/{id}")
    public ResponseEntity<Void> update(@PathVariable String id, @Valid @RequestBody UpdateDeviceRequest request) {
        devices.update(id, request.name(), request.roomId(), Boolean.TRUE.equals(request.clearRoom()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/devices/{id}")
    public ResponseEntity<Void> forget(@PathVariable String id) {
        devices.forget(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/quick-access")
    public ResponseEntity<Void> setQuickAccess(@Valid @RequestBody QuickAccessRequest request) {
        devices.setQuickAccess(request.items().stream().map(PinRequest::toItem).toList());
        return ResponseEntity.noContent().build();
    }
}
