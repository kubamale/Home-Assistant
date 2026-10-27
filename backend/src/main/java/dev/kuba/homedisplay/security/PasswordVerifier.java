package dev.kuba.homedisplay.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import dev.kuba.homedisplay.config.AppProperties;

/** Checks the single user's password against the configured bcrypt hash. */
@Component
public class PasswordVerifier {

    private static final Logger log = LoggerFactory.getLogger(PasswordVerifier.class);

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final String hash;

    public PasswordVerifier(AppProperties props) {
        String configuredHash = props.auth().passwordHash();
        String plain = props.auth().password();
        if (configuredHash != null && !configuredHash.isBlank()) {
            this.hash = configuredHash;
        } else if (plain != null && !plain.isBlank()) {
            log.warn("Using a plain APP_PASSWORD. Set APP_PASSWORD_HASH (bcrypt) for production.");
            this.hash = encoder.encode(plain);
        } else {
            throw new IllegalStateException("Set APP_PASSWORD_HASH (bcrypt) or, for development, APP_PASSWORD");
        }
    }

    public boolean matches(String candidate) {
        return candidate != null && encoder.matches(candidate, hash);
    }
}
