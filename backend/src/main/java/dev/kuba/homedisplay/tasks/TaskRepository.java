package dev.kuba.homedisplay.tasks;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class TaskRepository {

    private static final RowMapper<Task> MAPPER = (rs, n) -> new Task(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("due_date") == null ? null : LocalDate.parse(rs.getString("due_date")),
            rs.getInt("done") == 1,
            Instant.parse(rs.getString("created_at")),
            rs.getString("completed_at") == null ? null : Instant.parse(rs.getString("completed_at")));

    private final JdbcClient jdbc;

    public TaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Task> findAll() {
        return jdbc.sql("select * from task order by done, due_date is null, due_date, id")
                .query(MAPPER).list();
    }

    public Optional<Task> findById(long id) {
        return jdbc.sql("select * from task where id = ?").param(id).query(MAPPER).optional();
    }

    public Task insert(String title, LocalDate dueDate, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("insert into task (title, due_date, done, created_at) values (?, ?, 0, ?)")
                .params(title, dueDate == null ? null : dueDate.toString(), now.toString())
                .update(keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    public void update(long id, String title, LocalDate dueDate, boolean done, Instant completedAt) {
        jdbc.sql("update task set title = ?, due_date = ?, done = ?, completed_at = ? where id = ?")
                .params(title, dueDate == null ? null : dueDate.toString(), done ? 1 : 0,
                        completedAt == null ? null : completedAt.toString(), id)
                .update();
    }

    public boolean delete(long id) {
        return jdbc.sql("delete from task where id = ?").param(id).update() > 0;
    }
}
