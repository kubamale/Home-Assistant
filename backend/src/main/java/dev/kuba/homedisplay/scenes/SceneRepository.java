package dev.kuba.homedisplay.scenes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import dev.kuba.homedisplay.devices.ChannelChange;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SceneRepository {

    private static final RowMapper<Scene> MAPPER = (rs, n) ->
            new Scene(rs.getLong("id"), rs.getString("name"), rs.getInt("position"), rs.getString("kind"));

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public SceneRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Scene> findAll() {
        return jdbc.sql("select * from scene order by position, id").query(MAPPER).list();
    }

    public Optional<Scene> findById(long id) {
        return jdbc.sql("select * from scene where id = ?").param(id).query(MAPPER).optional();
    }

    /** Whether another scene already has this name, ignoring case. */
    public boolean nameTaken(String name, long exceptId) {
        return jdbc.sql("select count(*) from scene where lower(name) = lower(?) and id <> ?")
                .params(name, exceptId).query(Integer.class).single() > 0;
    }

    public Scene insert(String name) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("insert into scene (name, position, kind) values (?, (select coalesce(max(position), -1) + 1 from scene), 'custom')")
                .param(name).update(keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    public void rename(long id, String name) {
        jdbc.sql("update scene set name = ? where id = ?").params(name, id).update();
    }

    public boolean delete(long id) {
        return jdbc.sql("delete from scene where id = ?").param(id).update() > 0;
    }

    public void setPosition(long id, int position) {
        jdbc.sql("update scene set position = ? where id = ?").params(position, id).update();
    }

    /** The actions of every scene, in order, by scene id. */
    public Map<Long, List<SceneAction>> actionsByScene() {
        Map<Long, List<SceneAction>> out = new LinkedHashMap<>();
        jdbc.sql("select scene_id, device_id, channel, settings from scene_action order by scene_id, position, id")
                .query((rs, n) -> {
                    out.computeIfAbsent(rs.getLong("scene_id"), k -> new ArrayList<>()).add(new SceneAction(
                            rs.getString("device_id"), rs.getString("channel"),
                            json.readValue(rs.getString("settings"), ChannelChange.class)));
                    return null;
                }).list();
        return out;
    }

    public List<SceneAction> actions(long sceneId) {
        return actionsByScene().getOrDefault(sceneId, List.of());
    }

    /** Replaces all actions of a scene. */
    public void replaceActions(long sceneId, List<SceneAction> actions) {
        jdbc.sql("delete from scene_action where scene_id = ?").param(sceneId).update();
        for (int i = 0; i < actions.size(); i++) {
            SceneAction a = actions.get(i);
            jdbc.sql("insert into scene_action (scene_id, device_id, channel, settings, position) values (?, ?, ?, ?, ?)")
                    .params(sceneId, a.deviceId(), a.channel(), json.writeValueAsString(a.settings()), i).update();
        }
    }
}
