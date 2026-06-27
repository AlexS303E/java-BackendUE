package com.game.backend.serverauth.application;

import com.game.backend.serverauth.repository.ServerAuthRepository;

import com.game.backend.serverauth.config.ServerMtlsProperties;
import com.game.backend.serverauth.mtls.CertificateFingerprints;
import com.game.backend.serverauth.mtls.ClientCertificateExtractor;
import com.game.backend.serverauth.repository.ServerAuthRepository.ServerIdentityRecord;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Аутентифицирует /server/* запросы по server_id и mTLS client certificate fingerprint.
 */
@Component
public class ServerAuthenticationFilter extends OncePerRequestFilter {
    private static final String SERVER_ID_HEADER = "X-Server-Id";
    private static final String CERTIFICATE_FINGERPRINT_HEADER = "X-Server-Certificate-Fingerprint";

    private final ServerAuthRepository repository;
    private final ServerAuditService serverAuditService;
    private final ServerMtlsProperties mtlsProperties;
    private final ClientCertificateExtractor clientCertificateExtractor;
    private final MeterRegistry meterRegistry;

    public ServerAuthenticationFilter(
            ServerAuthRepository repository,
            ServerAuditService serverAuditService,
            ServerMtlsProperties mtlsProperties,
            ClientCertificateExtractor clientCertificateExtractor,
            MeterRegistry meterRegistry
    ) {
        this.repository = repository;
        this.serverAuditService = serverAuditService;
        this.mtlsProperties = mtlsProperties;
        this.clientCertificateExtractor = clientCertificateExtractor;
        this.meterRegistry = meterRegistry;
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

        UUID serverId = parseServerId(request, response);
        if (serverId == null) {
            return;
        }

        Optional<String> resolvedFingerprint = resolveCertificateFingerprint(request, serverId, requiredScope);
        if (resolvedFingerprint.isEmpty()) {
            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "mTLS client certificate is required for server API");
            return;
        }

        ServerIdentityRecord identityRecord = loadServerIdentityRecord(serverId);
        ServerAuthenticationFailure failure = validateIdentity(identityRecord, resolvedFingerprint.get());
        if (failure != null) {
            recordAuthenticationFailure(serverId, request, requiredScope, failure);
            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", failure.userMessage());
            return;
        }

        ServerIdentity identity = toPrincipal(identityRecord);
        if (!identity.hasScope(requiredScope)) {
            recordAuthDeniedMetric(request, requiredScope, "missing_scope");
            // Если identity валидна, но scope недостаточен, сохраняем denied audit event.
            serverAuditService.record(
                    identity,
                    null,
                    "server_auth.scope_denied",
                    requiredScope,
                    "denied",
                    Map.of(
                            "method", request.getMethod(),
                            "path", request.getRequestURI(),
                            "reason", "missing_scope"
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

    private UUID parseServerId(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String serverIdHeader = request.getHeader(SERVER_ID_HEADER);
        if (serverIdHeader == null || serverIdHeader.isBlank()) {
            recordAuthDeniedMetric(request, requiredScope(request), "missing_server_id");
            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Server identity header is required");
            return null;
        }

        try {
            return UUID.fromString(serverIdHeader);
        } catch (IllegalArgumentException exception) {
            recordAuthDeniedMetric(request, requiredScope(request), "invalid_server_id");
            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Server identity is invalid");
            return null;
        }
    }

    private Optional<String> resolveCertificateFingerprint(HttpServletRequest request, UUID serverId, String requiredScope) {
        if (mtlsProperties.isEnabled()) {
            if (mtlsProperties.isRequirePrivatePort() && request.getLocalPort() != mtlsProperties.getPort()) {
                recordAuthenticationFailure(
                        serverId,
                        request,
                        requiredScope,
                        new ServerAuthenticationFailure(
                                "wrong_private_port",
                                "Server API must be called through the private mTLS connector"
                        )
                );
                return Optional.empty();
            }

            Optional<X509Certificate> certificate = clientCertificateExtractor.firstClientCertificate(request);
            if (certificate.isEmpty()) {
                recordAuthenticationFailure(
                        serverId,
                        request,
                        requiredScope,
                        new ServerAuthenticationFailure(
                                "missing_client_certificate",
                                "mTLS client certificate is required for server API"
                        )
                );
            }
            return certificate.map(CertificateFingerprints::sha256Hex);
        }

        if (!mtlsProperties.isAllowHeaderFingerprintFallback()) {
            recordAuthenticationFailure(
                    serverId,
                    request,
                    requiredScope,
                    new ServerAuthenticationFailure(
                            "mtls_disabled_and_header_fallback_forbidden",
                            "mTLS client certificate is required for server API"
                    )
            );
            return Optional.empty();
        }

        String headerFingerprint = request.getHeader(CERTIFICATE_FINGERPRINT_HEADER);
        if (headerFingerprint == null || headerFingerprint.isBlank()) {
            recordAuthenticationFailure(
                    serverId,
                    request,
                    requiredScope,
                    new ServerAuthenticationFailure(
                            "missing_header_fingerprint_fallback",
                            "mTLS client certificate is required for server API"
                    )
            );
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
     * Загружает server identity по server_id. Status/fingerprint/expiry проверяются явно,
     * чтобы отказ можно было аудировать с конкретной причиной.
     */
    private ServerIdentityRecord loadServerIdentityRecord(UUID serverId) {
        List<ServerIdentityRecord> identities = repository.findServerIdentities(serverId);
        return identities.isEmpty() ? null : identities.getFirst();
    }

    private ServerAuthenticationFailure validateIdentity(ServerIdentityRecord identityRecord, String resolvedFingerprint) {
        if (identityRecord == null) {
            return new ServerAuthenticationFailure(
                    "unknown_server_identity",
                    "Server identity is not active or certificate fingerprint does not match"
            );
        }
        if (!"active".equals(identityRecord.status())) {
            return new ServerAuthenticationFailure(
                    "server_identity_" + identityRecord.status(),
                    "Server identity is not active or certificate fingerprint does not match"
            );
        }
        if (identityRecord.expiresAt() == null || !identityRecord.expiresAt().isAfter(OffsetDateTime.now())) {
            return new ServerAuthenticationFailure(
                    "server_identity_expired",
                    "Server identity is not active or certificate fingerprint does not match"
            );
        }
        String storedFingerprint = CertificateFingerprints.normalizeSha256Fingerprint(identityRecord.certificateFingerprint());
        if (storedFingerprint == null || !storedFingerprint.equals(resolvedFingerprint)) {
            return new ServerAuthenticationFailure(
                    "certificate_fingerprint_mismatch",
                    "Server identity is not active or certificate fingerprint does not match"
            );
        }
        return null;
    }

    private void recordAuthenticationFailure(
            UUID serverId,
            HttpServletRequest request,
            String requiredScope,
            ServerAuthenticationFailure failure
    ) {
        recordAuthDeniedMetric(request, requiredScope, failure.reason());
        if (!repository.serverIdentityExists(serverId)) {
            return;
        }
        serverAuditService.recordAuthenticationFailure(
                serverId,
                "server_auth.authentication_denied",
                requiredScope,
                Map.of(
                        "method", request.getMethod(),
                        "path", request.getRequestURI(),
                        "reason", failure.reason(),
                        "local_port", request.getLocalPort()
                )
        );
    }

    private void recordAuthDeniedMetric(HttpServletRequest request, String requiredScope, String reason) {
        meterRegistry.counter(
                "backend.server_auth.denials",
                "reason", reason,
                "scope", requiredScope == null ? "unknown" : requiredScope,
                "path", request.getRequestURI()
        ).increment();
    }

    private ServerIdentity toPrincipal(ServerIdentityRecord identityRecord) {
        return new ServerIdentity(
                identityRecord.serverId(),
                identityRecord.realmId(),
                identityRecord.serverBuildId(),
                identityRecord.allowedScopes()
        );
    }

    private void writeProblem(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write(
                "{\"title\":\"" + code + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}"
        );
    }

    private record ServerAuthenticationFailure(String reason, String userMessage) {
    }
}
