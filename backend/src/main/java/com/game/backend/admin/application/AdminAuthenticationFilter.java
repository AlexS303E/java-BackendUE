package com.game.backend.admin.application;

import com.game.backend.common.network.TrustedClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AdminAuthenticationFilter extends OncePerRequestFilter {
    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";
    private static final String ADMIN_CONFIRM_HEADER = "X-Admin-Confirm";

    private final AdminSecurityProperties properties;
    private final TrustedClientIpResolver clientIpResolver;

    public AdminAuthenticationFilter(AdminSecurityProperties properties, TrustedClientIpResolver clientIpResolver) {
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (!isAllowedIp(request)) {
            writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "ADMIN_IP_FORBIDDEN", "Admin IP is not allowed");
            return;
        }

        String token = request.getHeader(ADMIN_TOKEN_HEADER);
        if (properties.getToken() == null || properties.getToken().isBlank() || token == null || !properties.getToken().equals(token)) {
            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Admin token is required");
            return;
        }

        Set<String> roles = rolesFor();
        String requiredRole = requiredRole(request);
        if (requiredRole == null) {
            writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "ADMIN_ROUTE_FORBIDDEN", "Admin route is not assigned to a role");
            return;
        }
        if (!roles.contains(requiredRole)) {
            writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "ADMIN_ROLE_FORBIDDEN", "Admin role is required: " + requiredRole);
            return;
        }
        if (requiresConfirmation(request) && !isConfirmed(request)) {
            writeProblem(response, 428, "ADMIN_CONFIRMATION_REQUIRED", "Admin write action requires X-Admin-Confirm: true");
            return;
        }

        AdminIdentity identity = new AdminIdentity(actorIdFor(token), roles);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
            identity,
            "admin",
            roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_ADMIN_" + role.toUpperCase(Locale.ROOT)))
                .toList()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private Set<String> rolesFor() {
        return properties.getDefaultRoles().stream()
            .map(this::normalizeRole)
            .filter(role -> !role.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    }

    private String normalizeRole(String role) {
        return role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
    }

    private String requiredRole(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/admin/status/")) {
            return "status";
        }
        if (path.startsWith("/admin/catalog/")) {
            return "catalog";
        }
        if (path.contains("/server-identities") || path.contains("/outbox/") || path.contains("/cache/") || path.contains("-cache")) {
            return "ops";
        }
        if (path.startsWith("/admin/items/") || path.contains("/access/") || path.contains("/weapon-access")) {
            return "access";
        }
        return null;
    }

    private boolean requiresConfirmation(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private boolean isConfirmed(HttpServletRequest request) {
        String value = request.getHeader(ADMIN_CONFIRM_HEADER);
        return value != null && "true".equals(value.trim().toLowerCase(Locale.ROOT));
    }

    private boolean isAllowedIp(HttpServletRequest request) {
        if (properties.getAllowedCidrs().isEmpty()) {
            return true;
        }
        String clientIp = clientIpResolver.resolve(request);
        return properties.getAllowedCidrs().stream().anyMatch(rule -> ipMatches(clientIp, rule));
    }

    private String actorIdFor(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return "admin-token:"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(hash, 12));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available for admin credential identity", exception);
        }
    }

    private boolean ipMatches(String clientIp, String rule) {
        if (rule == null || rule.isBlank()) {
            return false;
        }
        String trimmed = rule.trim();
        if (!trimmed.contains("/")) {
            return trimmed.equals(clientIp);
        }
        try {
            String[] parts = trimmed.split("/", 2);
            byte[] address = java.net.InetAddress.getByName(clientIp).getAddress();
            byte[] network = java.net.InetAddress.getByName(parts[0]).getAddress();
            if (address.length != network.length) {
                return false;
            }
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > address.length * 8) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        } catch (Exception exception) {
            return false;
        }
    }

    private void writeProblem(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write(
            "{\"title\":\"" + code + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}"
        );
    }
}
