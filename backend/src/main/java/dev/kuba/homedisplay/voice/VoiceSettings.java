package dev.kuba.homedisplay.voice;

import java.util.List;

/**
 * How the assistant listens and sounds. {@code languages} are hints for the listening service (empty means detect);
 * the speaking service picks the language from the text itself. {@code wakeSensitivity} (low, normal or high) is how
 * easily a door screen takes a sound for its wake word, and {@code wakeReply} is what it says when it hears it, so the
 * person knows it is listening (empty: nothing).
 */
public record VoiceSettings(String voice, String style, List<String> languages, boolean speakReplies, String wakeSensitivity,
        String wakeReply) {

    public static final List<String> SENSITIVITIES = List.of("low", "normal", "high");
    public static final String DEFAULT_SENSITIVITY = "normal";
    public static final String DEFAULT_WAKE_REPLY = "Yes?";
    /** Short enough to be remembered by {@link SpokenPhrases} and so to be heard at once. */
    public static final int MAX_WAKE_REPLY = 40;

    public static VoiceSettings defaults() {
        return new VoiceSettings(GeminiVoices.DEFAULT, "", List.of("pl-PL", "en-US"), true, DEFAULT_SENSITIVITY, DEFAULT_WAKE_REPLY);
    }

    /**
     * The same settings with every field filled in: settings saved before the sensitivity and the wake reply existed have
     * none, which means the defaults (an empty reply is a choice, and stays).
     */
    public VoiceSettings normalized() {
        String sensitivity = wakeSensitivity != null && SENSITIVITIES.contains(wakeSensitivity) ? wakeSensitivity : DEFAULT_SENSITIVITY;
        String reply = wakeReply == null ? DEFAULT_WAKE_REPLY : wakeReply.trim();
        return new VoiceSettings(voice, style, languages, speakReplies, sensitivity, reply);
    }
}
