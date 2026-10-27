package dev.kuba.homedisplay.tasks;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import dev.kuba.homedisplay.settings.TimeService;
import dev.kuba.homedisplay.stream.ChangePublisher;

@Service
public class TaskService {

    /** A task as the clients see it. {@code overdue} is computed against today in the saved timezone. */
    public record TaskView(long id, String title, LocalDate dueDate, boolean done, boolean overdue) {
    }

    private final TaskRepository repository;
    private final TimeService time;
    private final ChangePublisher changes;
    private final Clock clock;

    public TaskService(TaskRepository repository, TimeService time, ChangePublisher changes, Clock clock) {
        this.repository = repository;
        this.time = time;
        this.changes = changes;
        this.clock = clock;
    }

    /**
     * Today's list: open tasks that are undated or due today or earlier, plus tasks completed today
     * (so a ticked task stays visible until the day ends).
     */
    public List<TaskView> today() {
        LocalDate today = time.today();
        return repository.findAll().stream()
                .filter(t -> t.done()
                        ? t.completedAt() != null && t.completedAt().atZone(time.zone()).toLocalDate().equals(today)
                        : t.dueDate() == null || !t.dueDate().isAfter(today))
                .map(t -> view(t, today))
                .toList();
    }

    /** Everything that is still open or was completed today, including future tasks. */
    public List<TaskView> all() {
        LocalDate today = time.today();
        return repository.findAll().stream()
                .filter(t -> !t.done()
                        || (t.completedAt() != null && t.completedAt().atZone(time.zone()).toLocalDate().equals(today)))
                .map(t -> view(t, today))
                .toList();
    }

    public TaskView create(String title, LocalDate dueDate) {
        Task task = repository.insert(title.trim(), dueDate, clock.instant());
        changes.publish(ChangePublisher.TASKS);
        return view(task, time.today());
    }

    /** Applies the given changes; null fields are left untouched ({@code clearDueDate} removes the date). */
    public Optional<TaskView> update(long id, String title, LocalDate dueDate, boolean clearDueDate, Boolean done) {
        return repository.findById(id).map(existing -> {
            String newTitle = title != null ? title.trim() : existing.title();
            LocalDate newDue = clearDueDate ? null : dueDate != null ? dueDate : existing.dueDate();
            boolean newDone = done != null ? done : existing.done();
            var completedAt = newDone ? (existing.done() ? existing.completedAt() : clock.instant()) : null;
            repository.update(id, newTitle, newDue, newDone, completedAt);
            changes.publish(ChangePublisher.TASKS);
            return view(repository.findById(id).orElseThrow(), time.today());
        });
    }

    public boolean delete(long id) {
        boolean deleted = repository.delete(id);
        if (deleted) {
            changes.publish(ChangePublisher.TASKS);
        }
        return deleted;
    }

    private static TaskView view(Task task, LocalDate today) {
        boolean overdue = !task.done() && task.dueDate() != null && task.dueDate().isBefore(today);
        return new TaskView(task.id(), task.title(), task.dueDate(), task.done(), overdue);
    }
}
