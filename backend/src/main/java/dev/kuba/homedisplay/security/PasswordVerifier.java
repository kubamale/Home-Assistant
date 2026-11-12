package dev.kuba.homedisplay.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import dev.kuba.homedisplay.config.AppProperties;

/**
 * Checks passwords. The admin's password is the one configured for the server (a bcrypt hash); every other
 * person's is a bcrypt hash stored with their account.
 */
@Component
public class PasswordVerifier {

    private static final Logger log = LoggerFactory.getLogger(PasswordVerifier.class);

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final String adminHash;
    /** Something to compare against when there is no such account, so a wrong name takes as long as a wrong password. */
    private final String decoyHash;

    public PasswordVerifier(AppProperties props) {
        String configuredHash = props.auth().passwordHash();
        String plain = props.auth().password();
        if (configuredHash != null && !configuredHash.isBlank()) {
            this.adminHash = configuredHash;
        } else if (plain != null && !plain.isBlank()) {
            log.warn("Using a plain APP_PASSWORD. Set APP_PASSWORD_HASH (bcrypt) for production.");
            this.adminHash = encoder.encode(plain);
        } else {
            throw new IllegalStateException("Set APP_PASSWORD_HASH (bcrypt) or, for development, APP_PASSWORD");
        }
        this.decoyHash = encoder.encode("decoy-" + System.nanoTime());
    }

    /** The admin's password. */
    public boolean matches(String candidate) {
        return candidate != null && encoder.matches(candidate, adminHash);
    }

    /** A person's password against their stored hash. */
    public boolean matches(String candidate, String hash) {
        return candidate != null && hash != null && encoder.matches(candidate, hash);
    }

    /** Spends the time a check would, for a login name that does not exist. */
    public void decoy(String candidate) {
        if (candidate != null) {
            encoder.matches(candidate, decoyHash);
        }
    }

    public String encode(String password) {
        return encoder.encode(password);
    }
}
