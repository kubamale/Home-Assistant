package dev.kuba.homedisplay.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Creates, verifies and revokes the tokens used by the phone, the door screen and the Pi agent. */
@Service
public class CredentialService {

    /** Avoid a database write on every request. */
    private static final Duration LAST_USED_GRANULARITY = Duration.ofHours(1);

    private static final RowMapper<Credential> MAPPER = (rs, n) -> new Credential(
            rs.getString("id"),
            CredentialKind.fromDb(rs.getString("kind")),
            rs.getString("label"),
            Instant.parse(rs.getString("created_at")),
            parse(rs.getString("last_used_at")),
            parse(rs.getString("revoked_at")),
            room(rs));

    private static Long room(java.sql.ResultSet rs) throws java.sql.SQLException {
        long id = rs.getLong("room_id");
        return rs.wasNull() ? null : id;
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public CredentialService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A newly created credential together with its raw token, which is shown exactly once. */
    public record Issued(Credential credential, String token) {
    }

    public Issued issue(CredentialKind kind, String label) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        jdbc.sql("insert into credential (id, kind, token_hash, label, created_at) values (?, ?, ?, ?, ?)")
                .params(id, kind.dbValue(), hash(token), label, now.toString())
                .update();
        return new Issued(new Credential(id, kind, label, now, null, null, null), token);
    }

    /** Returns the active credential for a raw token, or empty if unknown or revoked. */
    public Optional<Credential> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<Credential> found = jdbc
                .sql("select id, kind, label, created_at, last_used_at, revoked_at, room_id from credential where token_hash = ?")
                .param(hash(token))
                .query(MAPPER)
                .optional()
                .filter(c -> !c.revoked());
        found.ifPresent(this::touch);
        return found;
    }

    public List<Credential> list() {
        return jdbc.sql("""
                select id, kind, label, created_at, last_used_at, revoked_at, room_id
                from credential where revoked_at is null order by created_at""")
                .query(MAPPER)
                .list();
    }

    public boolean revoke(String id) {
        return jdbc.sql("update credential set revoked_at = ? where id = ? and revoked_at is null")
                .params(clock.instant().toString(), id)
                .update() > 0;
    }

    /** The room a display is in, if it was given one. Read when it is needed, so a change in Settings applies at once. */
    public Optional<Long> roomOf(String credentialId) {
        return jdbc.sql("select room_id from credential where id = ? and revoked_at is null")
                .param(credentialId)
                .query((rs, n) -> room(rs))
                .optional();
    }

    /** Puts a display in a room (null: in none). False when there is no such active credential. */
    public boolean setRoom(String id, Long roomId) {
        return jdbc.sql("update credential set room_id = ? where id = ? and revoked_at is null and kind = ?")
                .params(roomId, id, CredentialKind.DISPLAY.dbValue())
                .update() > 0;
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void touch(Credential credential) {
        Instant now = clock.instant();
        if (credential.lastUsedAt() == null
                || Duration.between(credential.lastUsedAt(), now).compareTo(LAST_USED_GRANULARITY) > 0) {
            jdbc.sql("update credential set last_used_at = ? where id = ?")
                    .params(now.toString(), credential.id())
                    .update();
        }
    }

    private static Instant parse(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
