package dev.kuba.homedisplay.calendar;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.kuba.homedisplay.security.SecretBox;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** A person's own calendar account. The password is kept encrypted and only ever read back by the server. */
@Repository
public class UserCalendarStore {

    /** What is saved. {@code calendars} are the names to show; empty means all of them. */
    public record Connection(String url, String username, String password, List<String> calendars, String updatedAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(UserCalendarStore.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SecretBox secrets;
    private final Clock clock;

    public UserCalendarStore(JdbcClient jdbc, JsonMapper json, SecretBox secrets, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.secrets = secrets;
        this.clock = clock;
    }

    /**
     * The person's connection. Empty when they have none, and also when the saved password can no longer be read (the
     * secret key was changed or removed): they then have to connect again, and nobody else's calendar is affected.
     */
    public Optional<Connection> find(long userId) {
        try {
            return jdbc.sql("select url, username, password, calendars, updated_at from user_calendar where user_id = ?")
                    .param(userId)
                    .query((rs, n) -> new Connection(rs.getString("url"), rs.getString("username"),
                            secrets.decrypt(rs.getString("password")),
                            json.readValue(rs.getString("calendars"), new TypeReference<List<String>>() { }),
                            rs.getString("updated_at")))
                    .optional();
        } catch (RuntimeException e) {
            log.warn("The saved calendar of person {} cannot be read, they need to connect it again: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    public List<Long> usersWithCalendars() {
        return jdbc.sql("select user_id from user_calendar").query(Long.class).list();
    }

    public void save(long userId, String url, String username, String password, List<String> calendars) {
        jdbc.sql("""
                insert into user_calendar (user_id, url, username, password, calendars, updated_at) values (?, ?, ?, ?, ?, ?)
                on conflict(user_id) do update set url = excluded.url, username = excluded.username,
                    password = excluded.password, calendars = excluded.calendars, updated_at = excluded.updated_at""")
                .params(userId, url, username, secrets.encrypt(password), json.writeValueAsString(calendars),
                        clock.instant().toString())
                .update();
    }

    public boolean delete(long userId) {
        return jdbc.sql("delete from user_calendar where user_id = ?").param(userId).update() > 0;
    }
}
