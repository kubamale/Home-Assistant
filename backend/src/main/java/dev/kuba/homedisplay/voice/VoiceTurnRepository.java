package dev.kuba.homedisplay.voice;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class VoiceTurnRepository {

    /** One logged question. {@code outcome} is ok, partial (some action failed) or error. */
    public record VoiceTurn(long id, Instant at, String source, String transcript, String reply, String toolsUsed,
            String outcome, long modelMs, long toolsMs) {
    }

    private final JdbcClient jdbc;

    public VoiceTurnRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Instant at, String source, String transcript, String reply, String toolsUsed, String outcome,
            long modelMs, long toolsMs) {
        jdbc.sql("""
                insert into voice_turn (at, source, transcript, reply, tools_used, outcome, model_ms, tools_ms)
                values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(at.toString(), source, transcript, reply, toolsUsed, outcome, modelMs, toolsMs)
                .update();
    }

    public List<VoiceTurn> recent(int limit) {
        return jdbc.sql("""
                select id, at, source, transcript, reply, tools_used, outcome, model_ms, tools_ms
                from voice_turn order by id desc limit ?""")
                .param(limit)
                .query((rs, n) -> new VoiceTurn(rs.getLong("id"), Instant.parse(rs.getString("at")), rs.getString("source"),
                        rs.getString("transcript"), rs.getString("reply"), rs.getString("tools_used"),
                        rs.getString("outcome"), rs.getLong("model_ms"), rs.getLong("tools_ms")))
                .list();
    }

    public int deleteBefore(Instant cutoff) {
        return jdbc.sql("delete from voice_turn where at < ?").param(cutoff.toString()).update();
    }
}
