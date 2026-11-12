package dev.kuba.homedisplay.calendar;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.kuba.homedisplay.security.CredentialPrincipal;
import dev.kuba.homedisplay.security.SecretBox;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Today's events for the person asking, and where that person's calendar comes from. */
@RestController
public class CalendarController {

    private final CalendarService calendar;
    private final SecretBox secrets;

    public CalendarController(CalendarService calendar, SecretBox secrets) {
        this.calendar = calendar;
        this.secrets = secrets;
    }

    /** {@code calendars} are the calendar names to show; empty or left out shows all of them. */
    public record ConnectRequest(@Size(max = 300) String url, @NotBlank @Size(max = 200) String username,
            @NotBlank @Size(max = 200) String password, List<@NotBlank @Size(max = 100) String> calendars) {
    }

    @GetMapping("/api/calendar/today")
    public CalendarService.Snapshot today(Authentication authentication) {
        return calendar.snapshot(user(authentication));
    }

    @GetMapping("/api/calendar/connection")
    public CalendarService.Connection connection(Authentication authentication) {
        return calendar.connection(user(authentication));
    }

    /** Connects the caller's own calendar (CalDAV, for example iCloud with an app-specific password). */
    @PutMapping("/api/calendar/connection")
    public CalendarService.Connection connect(@Valid @RequestBody ConnectRequest request, Authentication authentication) {
        if (!secrets.available()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Saving a calendar needs APP_SECRET_KEY to be set on the server");
        }
        long userId = user(authentication);
        String url = request.url() == null || request.url().isBlank() ? "https://caldav.icloud.com/" : request.url().trim();
        if (!url.startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The calendar address must start with https://");
        }
        rejectLocalAddress(url);
        try {
            calendar.connect(userId, url, request.username().trim(), request.password(),
                    request.calendars() == null ? List.of() : request.calendars().stream().map(String::trim).toList());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read that calendar: " + (e.getMessage() == null ? "unknown error" : e.getMessage()));
        }
        return calendar.connection(userId);
    }

    @DeleteMapping("/api/calendar/connection")
    public ResponseEntity<Void> disconnect(Authentication authentication) {
        calendar.disconnect(user(authentication));
        return ResponseEntity.noContent().build();
    }

    /**
     * The server makes this request with the person's credentials, so it must not be pointed at the server itself or at
     * a cloud metadata address. Other addresses on the home network are allowed: a self-hosted calendar lives there.
     */
    private static void rejectLocalAddress(String url) {
        try {
            for (java.net.InetAddress address : java.net.InetAddress.getAllByName(java.net.URI.create(url).getHost())) {
                if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That calendar address is not allowed");
                }
            }
        } catch (java.net.UnknownHostException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That calendar address cannot be found");
        }
    }

    private static long user(Authentication authentication) {
        return ((CredentialPrincipal) authentication.getPrincipal()).requireUser();
    }
}
