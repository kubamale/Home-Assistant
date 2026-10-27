package dev.kuba.homedisplay.security;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Cookie sessions plus SameSite=Lax already block cross-site form posts; this adds a second guard:
 * a state-changing browser request must come from the same origin it is sent to.
 */
public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !SAFE_METHODS.contains(request.getMethod()) && !sameOrigin(origin, request)) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "Cross-origin request rejected");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean sameOrigin(String origin, HttpServletRequest request) {
        try {
            String authority = URI.create(origin).getAuthority();
            String forwarded = request.getHeader("X-Forwarded-Host");
            String host = forwarded != null ? forwarded : request.getHeader("Host");
            return authority != null && authority.equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
