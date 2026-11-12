package dev.kuba.homedisplay.users;

import java.time.Instant;

/** A person with an account. The admin also manages the home itself: rooms, devices, the agent and who else exists. */
public record AppUser(long id, String name, boolean admin, Instant createdAt) {
}
