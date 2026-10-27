package dev.kuba.homedisplay.security;

public enum CredentialKind {
    USER("ROLE_USER"),
    DISPLAY("ROLE_DISPLAY"),
    AGENT("ROLE_AGENT");

    private final String authority;

    CredentialKind(String authority) {
        this.authority = authority;
    }

    public String authority() {
        return authority;
    }

    public String dbValue() {
        return name().toLowerCase();
    }

    public static CredentialKind fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
