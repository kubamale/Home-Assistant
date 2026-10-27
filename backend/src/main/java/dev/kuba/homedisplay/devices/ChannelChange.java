package dev.kuba.homedisplay.devices;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A partial update of one channel; null fields are left alone. Hue and saturation go together. */
public record ChannelChange(Boolean on, Integer brightness, Integer colorTemp, Integer hue, Integer saturation) {

    public boolean isEmpty() {
        return on == null && brightness == null && colorTemp == null && hue == null && saturation == null;
    }

    /** Rejects (HTTP 400) anything the channel cannot do or that is out of range. */
    public void validate(ChannelInfo channel) {
        Capabilities caps = channel.capabilities();
        if (isEmpty()) {
            throw bad("Nothing to change");
        }
        if (brightness != null) {
            if (!caps.brightness()) {
                throw bad(channel.label() + " has no brightness");
            }
            if (brightness < 1 || brightness > 100) {
                throw bad("Brightness must be 1 to 100");
            }
        }
        if (colorTemp != null) {
            if (caps.colorTemp() == null) {
                throw bad(channel.label() + " has no colour temperature");
            }
            if (colorTemp < caps.colorTemp().min() || colorTemp > caps.colorTemp().max()) {
                throw bad("Colour temperature must be " + caps.colorTemp().min() + " to " + caps.colorTemp().max() + " K");
            }
        }
        if ((hue == null) != (saturation == null)) {
            throw bad("Hue and saturation go together");
        }
        if (hue != null) {
            if (!caps.color()) {
                throw bad(channel.label() + " has no colour");
            }
            if (hue < 0 || hue > 359) {
                throw bad("Hue must be 0 to 359");
            }
            if (saturation < 0 || saturation > 100) {
                throw bad("Saturation must be 0 to 100");
            }
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
