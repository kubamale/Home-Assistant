package dev.kuba.homedisplay.security;

import java.time.Instant;

/** A stored client credential. The token itself is never kept, only its hash. */
public record Credential(
        String id,
        CredentialKind kind,
        String label,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt) {

    public boolean revoked() {
        return revokedAt != null;
    }
}
