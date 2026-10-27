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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Lets the user see and revoke paired displays, phones and the agent token. */
@RestController
@RequestMapping("/api/credentials")
public class CredentialController {

    private final CredentialService credentials;

    public CredentialController(CredentialService credentials) {
        this.credentials = credentials;
    }

    public record CredentialView(String id, String kind, String label, Instant createdAt, Instant lastUsedAt,
            boolean current) {
    }

    public record AgentTokenRequest(@NotBlank @Size(max = 60) String label) {
    }

    public record AgentTokenResponse(String id, String token) {
    }

    @GetMapping
    public List<CredentialView> list(Authentication authentication) {
        String currentId = ((CredentialPrincipal) authentication.getPrincipal()).credentialId();
        return credentials.list().stream()
                .map(c -> new CredentialView(c.id(), c.kind().dbValue(), c.label(), c.createdAt(), c.lastUsedAt(),
                        c.id().equals(currentId)))
                .toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id) {
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
