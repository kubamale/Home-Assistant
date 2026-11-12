package dev.kuba.homedisplay.displays;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.kuba.homedisplay.security.CredentialService;
import dev.kuba.homedisplay.stream.ChangePublisher;
import tools.jackson.databind.json.JsonMapper;

/** Reads and saves how each paired display looks. */
@Service
public class DisplayService {

    private static final Logger log = LoggerFactory.getLogger(DisplayService.class);

    private final CredentialService credentials;
    private final JsonMapper json;
    private final ChangePublisher changes;

    public DisplayService(CredentialService credentials, JsonMapper json, ChangePublisher changes) {
        this.credentials = credentials;
        this.json = json;
        this.changes = changes;
    }

    /** The display's layout, or the default until one was saved (or if the saved one cannot be read). */
    public DisplayLayout layout(String credentialId) {
        Optional<String> stored = credentials.layoutOf(credentialId).filter(s -> !s.isBlank());
        if (stored.isEmpty()) {
            return DisplayLayout.defaults();
        }
        try {
            return json.readValue(stored.get(), DisplayLayout.class).validated();
        } catch (RuntimeException e) {
            log.warn("The saved layout of display {} is unreadable, using the default: {}", credentialId, e.toString());
            return DisplayLayout.defaults();
        }
    }

    /** Checks and saves a layout. Returns it as saved (settings filled in). */
    public DisplayLayout save(String credentialId, DisplayLayout layout) {
        DisplayLayout checked = layout.validated();
        credentials.setLayout(credentialId, json.writeValueAsString(checked));
        changes.publish(ChangePublisher.DISPLAY);
        return checked;
    }

    /** Back to the default layout. */
    public DisplayLayout reset(String credentialId) {
        credentials.setLayout(credentialId, null);
        changes.publish(ChangePublisher.DISPLAY);
        return DisplayLayout.defaults();
    }
}
