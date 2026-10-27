package dev.kuba.homedisplay.tasks;

import java.time.Instant;
import java.time.LocalDate;

public record Task(long id, String title, LocalDate dueDate, boolean done, Instant createdAt, Instant completedAt) {
}
