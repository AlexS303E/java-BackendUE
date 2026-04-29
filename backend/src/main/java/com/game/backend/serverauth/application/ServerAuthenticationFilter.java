package com.game.backend.serverauth.application;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class ServerAuthenticationFilter extends OncePerRequestFilter {
    private static final String SERVER_ID_HEADER = "X-Server-Id";
    private static final String CERTIFICATE_FINGERPRINT_HEADER = "X-Server-Certificate-Fingerprint";

    private final JdbcTemplate jdbcTemplate;
    private final ServerAuditService serverAuditService;

    public ServerAuthenticationFilter(JdbcTemplate jdbcTemplate, ServerAuditService serverAuditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.serverAuditService = serverAuditService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/server/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String requiredScope = requiredScope(request);
        if (requiredScope == null) {
            writeProblem(response, HttpServletResponse.SC_NOT_FOUND, "SERVER_ENDPOINT_NOT_CONFIGURED", "Server endpoint is not configured");
            return;
        }

        String serverIdHeader = request.getHeader(SERVER_ID_HEADER);
        String fingerprint = request.getHeader(CERTIFICATE_FINGERPRINT_HEADER);
        if (serverIdHeader == null || serverIdHeader.isBlank() || fingerprint == null || fingerprint.isBlank()) {
            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Server identity headers are required");
            return;
        }

        UUID serverId;
        try {
            serverId = UUID.fromString(serverIdHeader);
        } catch (IllegalArgumentException exception) {
            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Server identity is invalid");
            return;
        }

        ServerIdentity identity = loadServerIdentity(serverId, fingerprint);
        if (identity == null) {
            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Server identity is not active or fingerprint does not match");
            return;
        }

        if (!identity.hasScope(requiredScope)) {
            serverAuditService.record(
                identity,
                null,
                "server_auth.scope_denied",
                requiredScope,
                "denied",
                Map.of(
                    "method", request.getMethod(),
                    "path", request.getRequestURI()
                )
            );
            writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Server identity does not have required scope: " + requiredScope);
            return;
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
            identity,
            "server",
            identity.allowedScopes()
                .stream()
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private String requiredScope(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("POST".equals(method) && "/server/match-profile/build".equals(path)) {
            return "match_profile:read";
        }
        if ("POST".equals(method) && "/server/runtime-preset-changes".equals(path)) {
            return "runtime_preset_change:write";
        }
        return null;
    }

    private ServerIdentity loadServerIdentity(UUID serverId, String fingerprint) {
        List<ServerIdentity> identities = jdbcTemplate.query(
            """
                SELECT server_id, realm_id, server_build_id, allowed_scopes
                FROM server_identities
                WHERE server_id = ?
                  AND certificate_fingerprint = ?
                  AND status = 'active'
                  AND expires_at > now()
                """,
            (rs, rowNum) -> new ServerIdentity(
                rs.getObject("server_id", UUID.class),
                rs.getString("realm_id"),
                rs.getString("server_build_id"),
                scopes(rs.getArray("allowed_scopes"))
            ),
            serverId,
            fingerprint
        );
        return identities.isEmpty() ? null : identities.getFirst();
    }

    private Set<String> scopes(Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        return Arrays.stream((String[]) array.getArray()).collect(Collectors.toSet());
    }

    private void writeProblem(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write(
            "{\"title\":\"" + code + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}"
        );
    }
}
