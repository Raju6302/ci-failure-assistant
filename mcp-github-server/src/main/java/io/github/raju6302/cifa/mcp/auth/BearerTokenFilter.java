package io.github.raju6302.cifa.mcp.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a request through only if it carries {@code Authorization: Bearer <shared token>}.
 * Health checks stay open so Docker and load balancers can probe the service.
 */
final class BearerTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final String HEALTH = "/actuator/health";

    private final byte[] expectedDigest;

    BearerTokenFilter(String token) {
        this.expectedDigest = sha256(token);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals(HEALTH) || path.startsWith(HEALTH + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAuthorized(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.getWriter().write("Missing or invalid bearer token\n");
    }

    private boolean isAuthorized(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return false;
        }
        // Comparing digests takes the same time whatever the input, so neither the token's
        // content nor its length can be found by timing responses.
        return MessageDigest.isEqual(expectedDigest, sha256(header.substring(BEARER.length()).strip()));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
