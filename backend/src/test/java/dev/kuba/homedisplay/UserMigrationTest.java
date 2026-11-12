package dev.kuba.homedisplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An installation from before there were accounts upgrades without losing anything: it all becomes the admin's. */
class UserMigrationTest {

    private static Flyway flyway(String url, String target) {
        return Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").target(target).load();
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void everythingThatExistedBeforeBelongsToTheAdmin(@TempDir Path dir) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("old.db") + "?foreign_keys=true";
        flyway(url, "6").migrate(); // the schema as the last release left it

        String at = "2026-10-01T00:00:00Z";
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("insert into task (title, done, created_at) values ('Old task', 0, '" + at + "'), ('Other', 1, '" + at + "')");
            st.execute("insert into credential (id, kind, token_hash, label, created_at, room_id) values "
                    + "('phone', 'user', 'h1', 'Phone', '" + at + "', null), ('door', 'display', 'h2', 'Door', '" + at + "', null), "
                    + "('pi', 'agent', 'h3', 'Pi', '" + at + "', null)");
            st.execute("insert into device (id, type, reported_name, first_seen_at, last_seen_at, channels) "
                    + "values ('lamp', 'yeelight', 'Lamp', '" + at + "', '" + at + "', '[]')");
            st.execute("insert into scene (name, position, kind) values ('Movie', 1, 'custom')");
            st.execute("insert into quick_access (position, device_id, channel, scene_id) values (1, 'lamp', 'main', null)");
            st.execute("insert into quick_access (position, device_id, channel, scene_id) "
                    + "select 2, null, null, id from scene where name = 'Movie'");
        }

        flyway(url, "latest").migrate();

        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            // one person, the admin, whose password stays the one configured on the server
            assertEquals(1, count(st, "select count(*) from app_user"));
            try (ResultSet rs = st.executeQuery("select id, admin, password_hash from app_user")) {
                rs.next();
                assertEquals(1, rs.getLong("id"));
                assertEquals(1, rs.getInt("admin"));
                assertNull(rs.getString("password_hash"));
            }
            assertEquals(2, count(st, "select count(*) from task where user_id = 1"));
            assertEquals(1, count(st, "select count(*) from credential where kind = 'user' and user_id = 1"));
            assertEquals(1, count(st, "select count(*) from credential where kind = 'display' and user_id = 1"));
            assertEquals(0, count(st, "select count(*) from credential where kind = 'agent' and user_id is not null"));
            // pins keep their slots, now the admin's
            assertEquals(2, count(st, "select count(*) from quick_access where user_id = 1"));
            assertEquals(1, count(st, "select position from quick_access where device_id = 'lamp'"));
            assertEquals(2, count(st, "select position from quick_access where scene_id is not null"));
            // scenes stay shared, so nobody loses one
            assertEquals(0, count(st, "select count(*) from scene where user_id is not null"));
            assertEquals(2, count(st, "select count(*) from scene"));
        }
    }
}
