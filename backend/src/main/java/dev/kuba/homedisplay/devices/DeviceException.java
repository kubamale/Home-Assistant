package dev.kuba.homedisplay.devices;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class DeviceException extends ResponseStatusException {

    public DeviceException(HttpStatus status, String message) {
        super(status, message);
    }

    public static DeviceException notFound(String id) {
        return new DeviceException(HttpStatus.NOT_FOUND, "No such device: " + id);
    }

    /** The agent that owns the devices is not connected. */
    public static DeviceException offline() {
        return new DeviceException(HttpStatus.SERVICE_UNAVAILABLE, "The home agent is offline");
    }

    /** The agent was reached but the device did not do what was asked. */
    public static DeviceException commandFailed(String reason) {
        return new DeviceException(HttpStatus.BAD_GATEWAY, reason);
    }
}
