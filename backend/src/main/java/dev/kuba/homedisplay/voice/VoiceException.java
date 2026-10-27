package dev.kuba.homedisplay.voice;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The assistant could not answer. The message is safe to show to the user. */
public class VoiceException extends ResponseStatusException {

    public VoiceException(HttpStatus status, String message) {
        super(status, message);
    }

    public static VoiceException notConfigured() {
        return new VoiceException(HttpStatus.SERVICE_UNAVAILABLE,
                "The assistant is not set up yet: the server needs an ANTHROPIC_API_KEY");
    }

    public static VoiceException unavailable() {
        return new VoiceException(HttpStatus.BAD_GATEWAY, "The assistant could not be reached, try again in a moment");
    }
}
