package dev.kuba.homedisplay.users;

import dev.kuba.homedisplay.security.CredentialPrincipal;

/**
 * The person a request is for: whose tasks, pins and scenes it sees, and whether they manage the home. A display is
 * its owner, but never an admin.
 */
public record Viewer(long userId, boolean admin) {

    public static Viewer of(CredentialPrincipal principal) {
        return new Viewer(principal.requireUser(), principal.admin());
    }
}
