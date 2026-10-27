package dev.kuba.homedisplay.security;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.kuba.homedisplay.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** A paired door screen should not need to be paired again. */
    private static final Duration DISPLAY_COOKIE_AGE = Duration.ofDays(365 * 5);

    private final PasswordVerifier passwords;
    private final CredentialService credentials;
    private final PairingService pairing;
    private final LoginRateLimiter limiter;
    private final AppProperties props;

    public AuthController(PasswordVerifier passwords, CredentialService credentials, PairingService pairing,
            LoginRateLimiter limiter, AppProperties props) {
        this.passwords = passwords;
        this.credentials = credentials;
        this.pairing = pairing;
        this.limiter = limiter;
        this.props = props;
    }

    public record LoginRequest(@NotBlank @Size(max = 200) String password) {
    }

    public record SessionInfo(String role, String label) {
    }

    public record PairStartResponse(String deviceCode, String userCode, String approvePath, long expiresIn,
            int pollIntervalSeconds) {
    }

    public record PairPollResponse(String status) {
    }

    public record PairApproveRequest(@NotBlank @Size(max = 32) String userCode, @Size(max = 60) String label) {
    }

    public record PairApproveResponse(String id, String label) {
    }

    @PostMapping("/login")
    public ResponseEntity<SessionInfo> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String key = "login:" + http.getRemoteAddr();
        limiter.check(key);
        if (!passwords.matches(request.password())) {
            limiter.failure(key);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong password");
        }
        limiter.success(key);
        CredentialService.Issued issued = credentials.issue(CredentialKind.USER, userAgentLabel(http));
        Duration maxAge = Duration.ofDays(props.auth().sessionDays());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, AuthCookies.session(issued.token(), maxAge, props).toString())
                .body(new SessionInfo("user", issued.credential().label()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        currentPrincipal(SecurityContextHolder.getContext().getAuthentication())
                .ifPresent(p -> credentials.revoke(p.credentialId()));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, AuthCookies.cleared(props).toString())
                .build();
    }

    @GetMapping("/me")
    public SessionInfo me(Authentication authentication) {
        CredentialPrincipal principal = (CredentialPrincipal) authentication.getPrincipal();
        return new SessionInfo(principal.kind().dbValue(), principal.label());
    }

    @PostMapping("/pair/start")
    public PairStartResponse pairStart(HttpServletRequest http) {
        limiter.check("pair-start:" + http.getRemoteAddr());
        limiter.failure("pair-start:" + http.getRemoteAddr()); // every start counts toward the window
        PairingService.Started started = pairing.start();
        return new PairStartResponse(started.deviceCode(), started.userCode(),
                "/approve?code=" + started.userCode(), started.expiresInSeconds(), 2);
    }

    @GetMapping("/pair/poll")
    public ResponseEntity<PairPollResponse> pairPoll(@RequestParam String deviceCode) {
        return switch (pairing.poll(deviceCode)) {
            case PairingService.PollResult.Waiting w -> ResponseEntity.accepted().body(new PairPollResponse("pending"));
            case PairingService.PollResult.Expired e -> ResponseEntity.status(HttpStatus.GONE)
                    .body(new PairPollResponse("expired"));
            case PairingService.PollResult.Approved a -> ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE,
                            AuthCookies.session(a.token(), DISPLAY_COOKIE_AGE, props).toString())
                    .body(new PairPollResponse("approved"));
        };
    }

    @PostMapping("/pair/approve")
    public PairApproveResponse pairApprove(@Valid @RequestBody PairApproveRequest request, Authentication authentication) {
        CredentialPrincipal principal = (CredentialPrincipal) authentication.getPrincipal();
        String key = "approve:" + principal.credentialId();
        limiter.check(key);
        String label = request.label() == null || request.label().isBlank() ? "Door screen" : request.label().trim();
        Credential credential = pairing.approve(request.userCode(), label).orElseGet(() -> {
            limiter.failure(key);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown or expired code");
        });
        return new PairApproveResponse(credential.id(), credential.label());
    }

    private static java.util.Optional<CredentialPrincipal> currentPrincipal(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CredentialPrincipal p) {
            return java.util.Optional.of(p);
        }
        return java.util.Optional.empty();
    }

    private static String userAgentLabel(HttpServletRequest http) {
        String agent = http.getHeader("User-Agent");
        if (agent == null || agent.isBlank()) {
            return "Phone";
        }
        return agent.length() > 60 ? agent.substring(0, 60) : agent;
    }
}
