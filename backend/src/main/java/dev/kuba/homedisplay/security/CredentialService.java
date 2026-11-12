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

    private static final String SELECT = """
            select c.id, c.kind, c.label, c.created_at, c.last_used_at, c.revoked_at, c.room_id, c.user_id,
                   coalesce(u.admin, 0) as admin, u.name as user_name
            from credential c left join app_user u on u.id = c.user_id""";

    private static final RowMapper<Credential> MAPPER = (rs, n) -> new Credential(
            rs.getString("id"),
            CredentialKind.fromDb(rs.getString("kind")),
            rs.getString("label"),
            Instant.parse(rs.getString("created_at")),
            parse(rs.getString("last_used_at")),
            parse(rs.getString("revoked_at")),
            nullableLong(rs, "room_id"),
            nullableLong(rs, "user_id"),
            rs.getInt("admin") == 1,
            rs.getString("user_name"));

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long id = rs.getLong(column);
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

    /** A credential that acts as no one in particular (the agent's). */
    public Issued issue(CredentialKind kind, String label) {
        return issue(kind, label, null);
    }

    /** A credential that acts as this person. */
    public Issued issue(CredentialKind kind, String label, Long userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        jdbc.sql("insert into credential (id, kind, token_hash, label, created_at, user_id) values (?, ?, ?, ?, ?, ?)")
                .params(id, kind.dbValue(), hash(token), label, now.toString(), userId)
                .update();
        return find(id).map(c -> new Issued(c, token)).orElseThrow();
    }

    /** Returns the active credential for a raw token, or empty if unknown or revoked. */
    public Optional<Credential> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<Credential> found = jdbc
                .sql(SELECT + " where c.token_hash = ?")
                .param(hash(token))
                .query(MAPPER)
                .optional()
                .filter(c -> !c.revoked());
        found.ifPresent(this::touch);
        return found;
    }

    public List<Credential> list() {
        return jdbc.sql(SELECT + " where c.revoked_at is null order by c.created_at").query(MAPPER).list();
    }

    /** What this person's own phones and displays are. */
    public List<Credential> listFor(long userId) {
        return jdbc.sql(SELECT + " where c.revoked_at is null and c.user_id = ? order by c.created_at")
                .param(userId).query(MAPPER).list();
    }

    /** An active credential by id. */
    public Optional<Credential> find(String id) {
        return jdbc.sql(SELECT + " where c.id = ? and c.revoked_at is null").param(id).query(MAPPER).optional();
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
                .query((rs, n) -> nullableLong(rs, "room_id"))
                .optional();
    }

    /** Puts a display in a room (null: in none). False when there is no such active credential. */
    public boolean setRoom(String id, Long roomId) {
        return jdbc.sql("update credential set room_id = ? where id = ? and revoked_at is null and kind = ?")
                .params(roomId, id, CredentialKind.DISPLAY.dbValue())
                .update() > 0;
    }

    /** Hands a display to another person. False when there is no such active display. */
    public boolean setUser(String id, long userId) {
        return jdbc.sql("update credential set user_id = ? where id = ? and revoked_at is null and kind = ?")
                .params(userId, id, CredentialKind.DISPLAY.dbValue())
                .update() > 0;
    }

    /** The stored layout JSON of a display, if it was ever set. */
    public Optional<String> layoutOf(String id) {
        return jdbc.sql("select layout from credential where id = ? and revoked_at is null and kind = ?")
                .params(id, CredentialKind.DISPLAY.dbValue())
                .query(String.class)
                .optional();
    }

    public boolean setLayout(String id, String layoutJson) {
        return jdbc.sql("update credential set layout = ? where id = ? and revoked_at is null and kind = ?")
                .params(layoutJson, id, CredentialKind.DISPLAY.dbValue())
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
