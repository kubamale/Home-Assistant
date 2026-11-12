package dev.kuba.homedisplay.rooms;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class RoomRepository {

    private static final RowMapper<Room> MAPPER = (rs, n) ->
            new Room(rs.getLong("id"), rs.getString("name"), rs.getInt("position"));

    private final JdbcClient jdbc;

    public RoomRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every room's setting and the people it names. */
    public Map<Long, RoomAccess.Policy> policies() {
        Map<Long, Set<Long>> members = new HashMap<>();
        jdbc.sql("select room_id, user_id from room_member").query((rs, n) -> {
            members.computeIfAbsent(rs.getLong("room_id"), k -> new HashSet<>()).add(rs.getLong("user_id"));
            return 0;
        }).list();
        Map<Long, RoomAccess.Policy> out = new HashMap<>();
        jdbc.sql("select id, access from room").query((rs, n) -> {
            long id = rs.getLong("id");
            out.put(id, new RoomAccess.Policy(rs.getString("access"), Set.copyOf(members.getOrDefault(id, Set.of()))));
            return 0;
        }).list();
        return out;
    }

    /** How many of these people have an account. */
    public int countUsers(Collection<Long> ids) {
        int found = 0;
        for (long id : ids) {
            found += jdbc.sql("select count(*) from app_user where id = ?").param(id).query(Integer.class).single();
        }
        return found;
    }

    public void setAccess(long id, String access, Collection<Long> members) {
        jdbc.sql("update room set access = ? where id = ?").params(access, id).update();
        jdbc.sql("delete from room_member where room_id = ?").param(id).update();
        for (long userId : members) {
            jdbc.sql("insert into room_member (room_id, user_id) values (?, ?)").params(id, userId).update();
        }
    }

    public List<Room> findAll() {
        return jdbc.sql("select * from room order by position, id").query(MAPPER).list();
    }

    public Optional<Room> findById(long id) {
        return jdbc.sql("select * from room where id = ?").param(id).query(MAPPER).optional();
    }

    /** Whether another room already has this name, ignoring case. */
    public boolean nameTaken(String name, long exceptId) {
        return jdbc.sql("select count(*) from room where lower(name) = lower(?) and id <> ?")
                .params(name, exceptId).query(Integer.class).single() > 0;
    }

    public Room insert(String name) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("insert into room (name, position) values (?, (select coalesce(max(position), -1) + 1 from room))")
                .param(name).update(keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    public boolean rename(long id, String name) {
        return jdbc.sql("update room set name = ? where id = ?").params(name, id).update() > 0;
    }

    public boolean delete(long id) {
        return jdbc.sql("delete from room where id = ?").param(id).update() > 0;
    }

    public void setPosition(long id, int position) {
        jdbc.sql("update room set position = ? where id = ?").params(position, id).update();
    }
}
