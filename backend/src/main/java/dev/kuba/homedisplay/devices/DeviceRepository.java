package dev.kuba.homedisplay.devices;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class DeviceRepository {

    private static final TypeReference<List<ChannelInfo>> CHANNELS = new TypeReference<>() {
    };

    private final RowMapper<StoredDevice> mapper = (rs, n) -> new StoredDevice(
            rs.getString("id"),
            rs.getString("type"),
            rs.getString("reported_name"),
            rs.getString("name_override"),
            rs.getObject("room_id") == null ? null : rs.getLong("room_id"),
            readChannels(rs.getString("channels")),
            Instant.parse(rs.getString("first_seen_at")),
            Instant.parse(rs.getString("last_seen_at")));

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DeviceRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Devices stored before channels existed have none; they are a single power-only light. */
    private List<ChannelInfo> readChannels(String text) {
        if (text == null || text.isBlank()) {
            return List.of(ChannelInfo.main());
        }
        return json.readValue(text, CHANNELS);
    }

    public List<StoredDevice> findAll() {
        return jdbc.sql("select * from device order by lower(coalesce(name_override, reported_name)), id")
                .query(mapper).list();
    }

    public Optional<StoredDevice> findById(String id) {
        return jdbc.sql("select * from device where id = ?").param(id).query(mapper).optional();
    }

    /** Inserts the device or refreshes what the agent reports about it, keeping the user's choices. */
    public void upsert(DiscoveredDevice device, Instant now) {
        jdbc.sql("""
                insert into device (id, type, reported_name, channels, first_seen_at, last_seen_at) values (?, ?, ?, ?, ?, ?)
                on conflict (id) do update set type = excluded.type, reported_name = excluded.reported_name,
                    channels = excluded.channels, last_seen_at = excluded.last_seen_at
                """)
                .params(device.id(), device.type(), device.name(), json.writeValueAsString(device.channels()),
                        now.toString(), now.toString())
                .update();
    }

    public void setRoom(String id, Long roomId) {
        jdbc.sql("update device set room_id = ? where id = ?").params(roomId, id).update();
    }

    public void setNameOverride(String id, String name) {
        jdbc.sql("update device set name_override = ? where id = ?").params(name, id).update();
    }

    public boolean delete(String id) {
        return jdbc.sql("delete from device where id = ?").param(id).update() > 0;
    }

    /** The pinned lights and scenes in slot order (slot 1 first). */
    public List<QuickAccessItem> quickAccess() {
        return jdbc.sql("select device_id, channel, scene_id from quick_access order by position")
                .query((rs, n) -> new QuickAccessItem(rs.getString("device_id"), rs.getString("channel"),
                        rs.getObject("scene_id") == null ? null : rs.getLong("scene_id"))).list();
    }

    /** Replaces all slots; slot numbers follow the list order starting at 1. */
    public void replaceQuickAccess(List<QuickAccessItem> items) {
        jdbc.sql("delete from quick_access").update();
        for (int i = 0; i < items.size(); i++) {
            QuickAccessItem item = items.get(i);
            jdbc.sql("insert into quick_access (position, device_id, channel, scene_id) values (?, ?, ?, ?)")
                    .params(i + 1, item.deviceId(), item.channel(), item.sceneId()).update();
        }
    }
}
