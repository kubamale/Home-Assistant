package dev.kuba.homedisplay.users;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.kuba.homedisplay.security.CredentialPrincipal;
import dev.kuba.homedisplay.security.LoginRateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Accounts. The admin lists, adds, renames and removes people and sets their passwords; everyone can change their own
 * password by giving the current one.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService users;
    private final LoginRateLimiter limiter;

    public UserController(UserService users, LoginRateLimiter limiter) {
        this.users = users;
        this.limiter = limiter;
    }

    public record CreateUserRequest(@NotBlank @Size(max = UserService.MAX_NAME) String name,
            @NotBlank @Size(max = UserService.MAX_PASSWORD) String password) {
    }

    public record RenameRequest(@NotBlank @Size(max = UserService.MAX_NAME) String name) {
    }

    /** The admin leaves {@code currentPassword} out when setting someone else's password. */
    public record PasswordRequest(@Size(max = UserService.MAX_PASSWORD) String currentPassword,
            @NotBlank @Size(max = UserService.MAX_PASSWORD) String password) {
    }

    @GetMapping
    public List<AppUser> list() {
        return users.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppUser create(@Valid @RequestBody CreateUserRequest request) {
        return users.create(request.name(), request.password());
    }

    @PutMapping("/{id}/name")
    public AppUser rename(@PathVariable long id, @Valid @RequestBody RenameRequest request) {
        return users.rename(id, request.name());
    }

    /** Sets a password. Security lets the admin do this for anyone; here a person may do it for themselves. */
    @PutMapping("/{id}/password")
    public ResponseEntity<Void> setPassword(@PathVariable long id, @Valid @RequestBody PasswordRequest request,
            Authentication authentication) {
        CredentialPrincipal who = (CredentialPrincipal) authentication.getPrincipal();
        boolean own = who.userId() != null && who.userId() == id;
        if (!own && !who.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only change your own password");
        }
        if (own && !who.admin()) {
            String key = "password:" + who.credentialId();
            limiter.check(key);
            if (request.currentPassword() == null || !users.passwordMatches(id, request.currentPassword())) {
                limiter.failure(key);
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The current password is wrong");
            }
            limiter.success(key);
        }
        users.setPassword(id, request.password(), own ? who.credentialId() : null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        users.delete(id);
        return ResponseEntity.noContent().build();
    }
}
