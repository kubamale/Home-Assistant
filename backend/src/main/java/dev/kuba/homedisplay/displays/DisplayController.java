package dev.kuba.homedisplay.displays;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.kuba.homedisplay.security.CredentialPrincipal;
import dev.kuba.homedisplay.users.AppUser;
import dev.kuba.homedisplay.users.UserService;

/** What a wall display asks for first: whose it is and how it should look. */
@RestController
@RequestMapping("/api/display")
public class DisplayController {

    private final DisplayService displays;
    private final UserService users;

    public DisplayController(DisplayService displays, UserService users) {
        this.displays = displays;
        this.users = users;
    }

    public record Owner(long id, String name) {
    }

    public record DisplayConfig(String label, Owner owner, DisplayLayout layout) {
    }

    @GetMapping("/config")
    public DisplayConfig config(Authentication authentication) {
        CredentialPrincipal who = (CredentialPrincipal) authentication.getPrincipal();
        AppUser owner = users.find(who.requireUser())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This display has no owner"));
        return new DisplayConfig(who.label(), new Owner(owner.id(), owner.name()), displays.layout(who.credentialId()));
    }
}
