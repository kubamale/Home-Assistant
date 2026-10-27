package dev.kuba.homedisplay.tasks;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.kuba.homedisplay.security.CredentialKind;
import dev.kuba.homedisplay.security.CredentialPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    public record CreateTaskRequest(@NotBlank @Size(max = 200) String title, LocalDate dueDate) {
    }

    /** All fields are optional. {@code clearDueDate} removes the due date; the door screen may only send {@code done}. */
    public record UpdateTaskRequest(@Size(min = 1, max = 200) String title, LocalDate dueDate, Boolean clearDueDate,
            Boolean done) {
    }

    @GetMapping
    public List<TaskService.TaskView> list(@RequestParam(defaultValue = "today") String scope) {
        return "all".equals(scope) ? tasks.all() : tasks.today();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskService.TaskView create(@Valid @RequestBody CreateTaskRequest request) {
        return tasks.create(request.title(), request.dueDate());
    }

    @PatchMapping("/{id}")
    public TaskService.TaskView update(@PathVariable long id, @Valid @RequestBody UpdateTaskRequest request,
            Authentication authentication) {
        CredentialPrincipal principal = (CredentialPrincipal) authentication.getPrincipal();
        boolean onlyDone = request.title() == null && request.dueDate() == null && request.clearDueDate() == null;
        if (principal.kind() == CredentialKind.DISPLAY && !onlyDone) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The door screen can only tick tasks");
        }
        return tasks.update(id, request.title(), request.dueDate(), Boolean.TRUE.equals(request.clearDueDate()),
                request.done())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such task"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        if (!tasks.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such task");
        }
        return ResponseEntity.noContent().build();
    }
}
