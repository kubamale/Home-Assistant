package dev.kuba.homedisplay.voice;

/** Listening or speaking failed. The message is safe to show to the user; it never contains the API key. */
public class SpeechException extends RuntimeException {

    public SpeechException(String message) {
        super(message);
    }

    public SpeechException(String message, Throwable cause) {
        super(message, cause);
    }
}
