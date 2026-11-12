package dev.kuba.homedisplay.security;

import java.time.Instant;

/**
 * A stored client credential. The token itself is never kept, only its hash. {@code userId} is the person a phone
 * login or a display acts as (none for the agent); {@code admin} and {@code userName} are that person's.
 */
public record Credential(
        String id,
        CredentialKind kind,
        String label,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt,
        Long roomId,
        Long userId,
        boolean admin,
        String userName) {

    public boolean revoked() {
        return revokedAt != null;
    }
}
