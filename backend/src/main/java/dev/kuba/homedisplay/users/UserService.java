package dev.kuba.homedisplay.users;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import dev.kuba.homedisplay.security.PasswordVerifier;

/**
 * The people who have accounts. The admin (the one who set the home up) signs in with the password configured for
 * the server; the others with a password the admin chose for them, which they can change.
 */
@Service
public class UserService {

    public static final int MIN_PASSWORD = 8;
    public static final int MAX_PASSWORD = 200;
    public static final int MAX_NAME = 40;

    private static final RowMapper<AppUser> MAPPER = (rs, n) -> new AppUser(
            rs.getLong("id"), rs.getString("name"), rs.getInt("admin") == 1, Instant.parse(rs.getString("created_at")));

    private final JdbcClient jdbc;
    private final PasswordVerifier passwords;
    private final Clock clock;

    public UserService(JdbcClient jdbc, PasswordVerifier passwords, Clock clock) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.clock = clock;
    }

    public List<AppUser> list() {
        return jdbc.sql("select id, name, admin, created_at from app_user order by admin desc, lower(name)")
                .query(MAPPER).list();
    }

    public Optional<AppUser> find(long id) {
        return jdbc.sql("select id, name, admin, created_at from app_user where id = ?").param(id).query(MAPPER).optional();
    }

    /**
     * Checks a login. A blank name means the admin, which is how the single-password installation signs in.
     * Empty when the name or the password is wrong; the two cannot be told apart.
     */
    public Optional<AppUser> authenticate(String name, String password) {
        boolean anyone = name == null || name.isBlank();
        record Row(long id, String name, boolean admin, Instant createdAt, String hash) {
        }
        Optional<Row> row = anyone
                ? jdbc.sql("select id, name, admin, created_at, password_hash from app_user where admin = 1 order by id limit 1")
                        .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("name"), true,
                                Instant.parse(rs.getString("created_at")), rs.getString("password_hash"))).optional()
                : jdbc.sql("select id, name, admin, created_at, password_hash from app_user where lower(name) = lower(?)")
                        .param(name.trim())
                        .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("name"), rs.getInt("admin") == 1,
                                Instant.parse(rs.getString("created_at")), rs.getString("password_hash"))).optional();
        if (row.isEmpty()) {
            passwords.decoy(password);
            return Optional.empty();
        }
        Row found = row.get();
        // The admin has no stored hash: their password is the server's configured one.
        boolean ok = found.hash() == null ? found.admin() && passwords.matches(password) : passwords.matches(password, found.hash());
        return ok ? Optional.of(new AppUser(found.id(), found.name(), found.admin(), found.createdAt())) : Optional.empty();
    }

    public AppUser create(String name, String password) {
        String trimmed = validName(name);
        validPassword(password);
        if (nameTaken(trimmed, -1)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Someone with that name already exists");
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("insert into app_user (name, password_hash, admin, created_at) values (?, ?, 0, ?)")
                .params(trimmed, passwords.encode(password), clock.instant().toString()).update(keys);
        return find(keys.getKey().longValue()).orElseThrow();
    }

    public AppUser rename(long id, String name) {
        find(id).orElseThrow(UserService::notFound);
        String trimmed = validName(name);
        if (nameTaken(trimmed, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Someone with that name already exists");
        }
        jdbc.sql("update app_user set name = ? where id = ?").params(trimmed, id).update();
        return find(id).orElseThrow();
    }

    /**
     * Sets a new password. The admin's is not stored, so it cannot be changed here. The person's phones are signed out,
     * except {@code keepCredentialId} (the one they are changing it from, if it is theirs).
     */
    public void setPassword(long id, String password, String keepCredentialId) {
        AppUser user = find(id).orElseThrow(UserService::notFound);
        if (user.admin()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The admin's password is set on the server (APP_PASSWORD_HASH)");
        }
        validPassword(password);
        jdbc.sql("update app_user set password_hash = ? where id = ?").params(passwords.encode(password), id).update();
        // Signing in again is the point of changing it: end the sessions that used the old one.
        jdbc.sql("""
                update credential set revoked_at = ?
                where user_id = ? and kind = 'user' and revoked_at is null and id <> ?""")
                .params(clock.instant().toString(), id, keepCredentialId == null ? "" : keepCredentialId).update();
    }

    /** Whether this is the person's current password (so a change of password can ask for it). */
    public boolean passwordMatches(long id, String password) {
        return jdbc.sql("select password_hash from app_user where id = ?").param(id).query(String.class).optional()
                .map(hash -> passwords.matches(password, hash)).orElse(false);
    }

    /** Removes a person with their tasks, pins, calendar, own scenes, phone logins and displays. The admin cannot be removed. */
    public void delete(long id) {
        AppUser user = find(id).orElseThrow(UserService::notFound);
        if (user.admin()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The admin cannot be removed");
        }
        jdbc.sql("delete from app_user where id = ?").param(id).update();
    }

    private boolean nameTaken(String name, long exceptId) {
        return jdbc.sql("select count(*) from app_user where lower(name) = lower(?) and id <> ?")
                .params(name, exceptId).query(Integer.class).single() > 0;
    }

    private static String validName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The name must be 1 to " + MAX_NAME + " characters");
        }
        return trimmed;
    }

    private static void validPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The password must be " + MIN_PASSWORD + " to " + MAX_PASSWORD + " characters");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person");
    }
}
