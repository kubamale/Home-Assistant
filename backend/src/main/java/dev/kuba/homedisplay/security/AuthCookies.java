package dev.kuba.homedisplay.security;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

import dev.kuba.homedisplay.config.AppProperties;

/** Builds the session cookie shared by the phone login and the paired door screen. */
public final class AuthCookies {

    public static final String NAME = "hd_session";

    private AuthCookies() {
    }

    public static ResponseCookie session(String token, Duration maxAge, AppProperties props) {
        return ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(props.auth().secureCookies())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    public static ResponseCookie cleared(AppProperties props) {
        return session("", Duration.ZERO, props);
    }
}
