package dev.kuba.homedisplay.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Display pairing in the device-code style: the unpaired door screen asks to pair and shows a short
 * code (also as a QR link). The signed-in phone approves that code; only the screen, which holds
 * the secret device code, can then collect the token. Pending pairings live in memory only.
 */
@Service
public class PairingService {

    static final Duration TTL = Duration.ofMinutes(10);
    static final int MAX_PENDING = 20;
    /** No 0/O, 1/I/L to keep codes easy to read and type. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    private final CredentialService credentials;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public PairingService(CredentialService credentials, Clock clock) {
        this.credentials = credentials;
        this.clock = clock;
    }

    private record Pending(String deviceCode, String userCode, Instant expiresAt, String token) {
    }

    public record Started(String deviceCode, String userCode, long expiresInSeconds) {
    }

    public sealed interface PollResult {
        record Waiting() implements PollResult {
        }

        record Approved(String token) implements PollResult {
        }

        record Expired() implements PollResult {
        }
    }

    public synchronized Started start() {
        prune();
        if (pending.size() >= MAX_PENDING) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many pairing requests");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String deviceCode = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        Instant expires = clock.instant().plus(TTL);
        pending.put(deviceCode, new Pending(deviceCode, code.toString(), expires, null));
        return new Started(deviceCode, format(code.toString()), TTL.toSeconds());
    }

    /** Approves the pairing with this code. Empty if the code is unknown or expired. */
    public synchronized Optional<Credential> approve(String userCodeInput, String label) {
        prune();
        String normalized = normalize(userCodeInput);
        for (Pending candidate : pending.values()) {
            if (candidate.token() == null && constantTimeEquals(candidate.userCode(), normalized)) {
                CredentialService.Issued issued = credentials.issue(CredentialKind.DISPLAY, label);
                pending.put(candidate.deviceCode(), new Pending(
                        candidate.deviceCode(), candidate.userCode(), candidate.expiresAt(), issued.token()));
                return Optional.of(issued.credential());
            }
        }
        return Optional.empty();
    }

    /** The token is handed over exactly once, then the pairing is forgotten. */
    public synchronized PollResult poll(String deviceCode) {
        prune();
        Pending found = deviceCode == null ? null : pending.get(deviceCode);
        if (found == null) {
            return new PollResult.Expired();
        }
        if (found.token() == null) {
            return new PollResult.Waiting();
        }
        pending.remove(deviceCode);
        return new PollResult.Approved(found.token());
    }

    private void prune() {
        Instant now = clock.instant();
        pending.values().removeIf(p -> p.expiresAt().isBefore(now));
    }

    private static String normalize(String input) {
        return input == null ? "" : input.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private static String format(String code) {
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
