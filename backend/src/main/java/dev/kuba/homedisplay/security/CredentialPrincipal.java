package dev.kuba.homedisplay.security;

/**
 * Who is calling: the id and kind of the credential that authenticated the request, and the person it acts as
 * ({@code userId} is null for the agent). {@code admin} is true only for the admin's phone login; a display never
 * manages the home, even the admin's.
 */
public record CredentialPrincipal(String credentialId, CredentialKind kind, String label, Long userId, boolean admin) {

    /** The person's id, for code that only runs for a phone or a display. */
    public long requireUser() {
        if (userId == null) {
            throw new IllegalStateException("This credential does not act as a person");
        }
        return userId;
    }
}
