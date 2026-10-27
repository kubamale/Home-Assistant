package dev.kuba.homedisplay.rooms;

import java.util.List;
import java.util.Optional;

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
