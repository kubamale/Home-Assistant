package dev.kuba.homedisplay.security;

import java.time.Instant;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.kuba.homedisplay.displays.DisplayLayout;
import dev.kuba.homedisplay.displays.DisplayService;
import dev.kuba.homedisplay.rooms.RoomService;
import dev.kuba.homedisplay.stream.ChangePublisher;
import dev.kuba.homedisplay.users.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Lets a person see and revoke their own phones and displays, and say which room a display is in. The admin sees and
 * manages everyone's, and hands a display to someone else.
 */
@RestController
@RequestMapping("/api/credentials")
public class CredentialController {

    private final CredentialService credentials;
    private final RoomService rooms;
    private final UserService users;
    private final DisplayService displays;
    private final ChangePublisher changes;

    public CredentialController(CredentialService credentials, RoomService rooms, UserService users,
            DisplayService displays, ChangePublisher changes) {
        this.changes = changes;
        this.credentials = credentials;
        this.rooms = rooms;
        this.users = users;
        this.displays = displays;
    }

    public record CredentialView(String id, String kind, String label, Instant createdAt, Instant lastUsedAt,
            boolean current, Long roomId, Long userId, String userName) {
    }

    /** The person a display belongs to. */
    public record OwnerRequest(@NotNull Long userId) {
    }

    /** The room a display is in; null takes it out of its room. */
    public record RoomRequest(Long roomId) {
    }

    public record AgentTokenRequest(@NotBlank @Size(max = 60) String label) {
    }

    public record AgentTokenResponse(String id, String token) {
    }

    @GetMapping
    public List<CredentialView> list(Authentication authentication) {
        CredentialPrincipal who = (CredentialPrincipal) authentication.getPrincipal();
        List<Credential> visible = who.admin() ? credentials.list() : credentials.listFor(who.requireUser());
        return visible.stream()
                .map(c -> new CredentialView(c.id(), c.kind().dbValue(), c.label(), c.createdAt(), c.lastUsedAt(),
                        c.id().equals(who.credentialId()), c.roomId(), c.userId(), c.userName()))
                .toList();
    }

    /** The credential, if the caller may manage it: their own, or anyone's for the admin. Others look like they do not exist. */
    private Credential manageable(String id, Authentication authentication) {
        CredentialPrincipal who = (CredentialPrincipal) authentication.getPrincipal();
        return credentials.find(id)
                .filter(c -> who.admin() || (c.userId() != null && c.userId().equals(who.userId())))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such display"));
    }

    /** Says which room a display is in. Only displays have one: a phone goes wherever its owner does. */
    @PutMapping("/{id}/room")
    public ResponseEntity<Void> setRoom(@PathVariable String id, @RequestBody RoomRequest request,
            Authentication authentication) {
        manageable(id, authentication);
        if (request.roomId() != null && rooms.list().stream().noneMatch(r -> r.id() == request.roomId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such room");
        }
        if (!credentials.setRoom(id, request.roomId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such display");
        }
        return ResponseEntity.noContent().build();
    }

    /** How a display looks (the default until it was changed). */
    @GetMapping("/{id}/layout")
    public DisplayLayout layout(@PathVariable String id, Authentication authentication) {
        return displays.layout(display(id, authentication).id());
    }

    @PutMapping("/{id}/layout")
    public DisplayLayout setLayout(@PathVariable String id, @RequestBody DisplayLayout layout, Authentication authentication) {
        return displays.save(display(id, authentication).id(), layout);
    }

    /** Back to the default layout. */
    @DeleteMapping("/{id}/layout")
    public DisplayLayout resetLayout(@PathVariable String id, Authentication authentication) {
        return displays.reset(display(id, authentication).id());
    }

    private Credential display(String id, Authentication authentication) {
        Credential credential = manageable(id, authentication);
        if (credential.kind() != CredentialKind.DISPLAY) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such display");
        }
        return credential;
    }

    /** Hands a display to another person (admin only). Its layout stays; their tasks, pins and calendar replace the old owner's. */
    @PutMapping("/{id}/user")
    public ResponseEntity<Void> setUser(@PathVariable String id, @Valid @RequestBody OwnerRequest request) {
        if (users.find(request.userId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person");
        }
        if (!credentials.setUser(id, request.userId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such display");
        }
        changes.publish(ChangePublisher.DISPLAY); // the display now shows someone else's tasks, pins and calendar
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id, Authentication authentication) {
        manageable(id, authentication);
        if (!credentials.revoke(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such credential");
        }
        return ResponseEntity.noContent().build();
    }

    /** Issues the token for the Pi agent. It is shown once; only its hash is stored. */
    @PostMapping("/agent")
    public AgentTokenResponse createAgentToken(@Valid @RequestBody AgentTokenRequest request) {
        CredentialService.Issued issued = credentials.issue(CredentialKind.AGENT, request.label().trim());
        return new AgentTokenResponse(issued.credential().id(), issued.token());
    }
}
