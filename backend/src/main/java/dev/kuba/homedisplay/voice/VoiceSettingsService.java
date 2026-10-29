package dev.kuba.homedisplay.voice;

import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import dev.kuba.homedisplay.stream.ChangePublisher;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Stores how the assistant sounds. */
@Service
public class VoiceSettingsService {

    private static final String KEY = "voice.settings";
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}-[A-Z]{2}");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final ChangePublisher changes;

    public VoiceSettingsService(JdbcClient jdbc, JsonMapper json, Clock clock, ChangePublisher changes) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.changes = changes;
    }

    /** The saved settings, or the defaults until something was saved. A stored value that no longer reads falls back too. */
    public VoiceSettings get() {
        return jdbc.sql("select value from setting where key = ?").param(KEY).query(String.class).optional().map(value -> {
            try {
                VoiceSettings saved = json.readValue(value, VoiceSettings.class);
                return GeminiVoices.NAMES.contains(saved.voice()) ? saved.normalized() : VoiceSettings.defaults();
            } catch (JacksonException e) {
                return VoiceSettings.defaults();
            }
        }).orElseGet(VoiceSettings::defaults);
    }

    public VoiceSettings save(VoiceSettings settings) {
        validate(settings);
        VoiceSettings clean = new VoiceSettings(settings.voice(), settings.style() == null ? "" : settings.style().trim(),
                List.copyOf(settings.languages()), settings.speakReplies(), settings.wakeSensitivity(), settings.wakeReply()).normalized();
        jdbc.sql("""
                insert into setting (key, value, updated_at) values (?, ?, ?)
                on conflict(key) do update set value = excluded.value, updated_at = excluded.updated_at""")
                .params(KEY, json.writeValueAsString(clean), clock.instant().toString())
                .update();
        changes.publish(ChangePublisher.SETTINGS);
        return clean;
    }

    private static void validate(VoiceSettings settings) {
        if (!GeminiVoices.NAMES.contains(settings.voice())) {
            throw bad("Unknown voice");
        }
        if (settings.style() != null && settings.style().length() > 200) {
            throw bad("The style is limited to 200 characters");
        }
        if (settings.languages() == null || settings.languages().size() > 4) {
            throw bad("Give at most four languages");
        }
        if (settings.wakeSensitivity() != null && !VoiceSettings.SENSITIVITIES.contains(settings.wakeSensitivity())) {
            throw bad("The wake word sensitivity is low, normal or high");
        }
        if (settings.wakeReply() != null && settings.wakeReply().trim().length() > VoiceSettings.MAX_WAKE_REPLY) {
            throw bad("What it says after the wake word is limited to " + VoiceSettings.MAX_WAKE_REPLY + " characters");
        }
        for (String language : settings.languages()) {
            if (!LANGUAGE.matcher(language).matches()) {
                throw bad("Languages look like pl-PL or en-US");
            }
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
