package com.game.backend.serverauth.application;

import com.game.backend.serverauth.config.ServerMtlsProperties;
import com.game.backend.serverauth.mtls.CertificateFingerprints;
import com.game.backend.serverauth.mtls.ClientCertificateExtractor;
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
import java.security.cert.X509Certificate;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Аутентифицирует /server/* запросы по server_id и mTLS client certificate fingerprint.
 */
@Component
public class ServerAuthenticationFilter extends OncePerRequestFilter {
    private static final String SERVER_ID_HEADER = "X-Server-Id";
    private static final String CERTIFICATE_FINGERPRINT_HEADER = "X-Server-Certificate-Fingerprint";

    private final JdbcTemplate jdbcTemplate;
    private final ServerAuditService serverAuditService;
    private final ServerMtlsProperties mtlsProperties;
    private final ClientCertificateExtractor clientCertificateExtractor;

    public ServerAuthenticationFilter(
            JdbcTemplate jdbcTemplate,
            ServerAuditService serverAuditService,
            ServerMtlsProperties mtlsProperties,
            ClientCertificateExtractor clientCertificateExtractor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.serverAuditService = serverAuditService;
        this.mtlsProperties = mtlsProperties;
        this.clientCertificateExtractor = clientCertificateExtractor;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/server/");
    }

    /**
     * Проверяет server identity, required scope и кладет ServerIdentity в SecurityContext.
     */
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
        if (serverIdHeader == null || serverIdHeader.isBlank()) {
            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Server identity header is required");
            return;
        }

        UUID serverId;
        try {
            serverId = UUID.fromString(serverIdHeader);
        } catch (IllegalArgumentException exception) {
            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Server identity is invalid");
            return;
        }

        Optional<String> resolvedFingerprint = resolveCertificateFingerprint(request);
        if (resolvedFingerprint.isEmpty()) {
            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "mTLS client certificate is required for server API");
            return;
        }

        ServerIdentity identity = loadServerIdentity(serverId, resolvedFingerprint.get());
        if (identity == null) {
            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Server identity is not active or certificate fingerprint does not match");
            return;
        }

        if (!identity.hasScope(requiredScope)) {
            // Если identity валидна, но scope недостаточен, сохраняем denied audit event.
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

    private Optional<String> resolveCertificateFingerprint(HttpServletRequest request) {
        if (mtlsProperties.isEnabled()) {
            if (mtlsProperties.isRequirePrivatePort() && request.getLocalPort() != mtlsProperties.getPort()) {
                return Optional.empty();
            }

            Optional<X509Certificate> certificate = clientCertificateExtractor.firstClientCertificate(request);
            return certificate.map(CertificateFingerprints::sha256Hex);
        }

        if (!mtlsProperties.isAllowHeaderFingerprintFallback()) {
            return Optional.empty();
        }

        String headerFingerprint = request.getHeader(CERTIFICATE_FINGERPRINT_HEADER);
        if (headerFingerprint == null || headerFingerprint.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CertificateFingerprints.normalizeSha256Fingerprint(headerFingerprint));
    }

    /**
     * Жесткая route -> scope матрица для текущего MVP набора server endpoints.
     */
    private String requiredScope(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("POST".equals(method) && "/server/match-profile/build".equals(path)) {
            return "match_profile:read";
        }
        if ("POST".equals(method) && "/server/runtime-preset-changes".equals(path)) {
            return "runtime_preset_change:write";
        }
        if ("POST".equals(method) && "/server/runtime-events".equals(path)) {
            return "runtime_event:write";
        }
        return null;
    }

    /**
     * Загружает только active server identity с совпавшим fingerprint и неистекшим сроком.
     */
    private ServerIdentity loadServerIdentity(UUID serverId, String fingerprint) {
        List<ServerIdentity> identities = jdbcTemplate.query(
                """
                    SELECT server_id, realm_id, server_build_id, allowed_scopes
                    FROM server_identities
                    WHERE server_id = ?
                      AND lower(replace(certificate_fingerprint, ':', '')) = ?
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
