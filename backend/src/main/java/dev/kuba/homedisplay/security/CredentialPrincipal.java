package dev.kuba.homedisplay.security;

/** Who is calling: the id and kind of the credential that authenticated the request. */
public record CredentialPrincipal(String credentialId, CredentialKind kind, String label) {
}
