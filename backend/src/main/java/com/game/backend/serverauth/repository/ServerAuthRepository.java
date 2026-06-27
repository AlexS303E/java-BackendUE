package com.game.backend.serverauth.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class ServerAuthRepository extends JdbcRepository {
    public record ServerMatch(
        UUID matchId,
        UUID serverId,
        String realmId,
        String status
    ) {
    }

    public record ServerIdentityRecord(
        UUID serverId,
        String realmId,
        String serverBuildId,
        String certificateFingerprint,
        Set<String> acceptedCertificateFingerprints,
        String status,
        Set<String> allowedScopes,
        OffsetDateTime expiresAt
    ) {
    }

    public ServerAuthRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public List<ServerMatch> insertMatchIfAbsent(
        UUID matchId,
        UUID serverId,
        String realmId,
        OffsetDateTime now
    ) {
        return query(
            """
                INSERT INTO server_matches(
                  match_id,
                  server_id,
                  realm_id,
                  status,
                  created_at
                )
                VALUES (?, ?, ?, 'running', ?)
                ON CONFLICT (match_id) DO NOTHING
                RETURNING match_id, server_id, realm_id, status
                """,
            (rs, rowNum) -> new ServerMatch(
                rs.getObject("match_id", UUID.class),
                rs.getObject("server_id", UUID.class),
                rs.getString("realm_id"),
                rs.getString("status")
            ),
            matchId,
            serverId,
            realmId,
            now
        );
    }

    public List<ServerMatch> findMatches(UUID matchId) {
        return query(
            """
                SELECT match_id, server_id, realm_id, status
                FROM server_matches
                WHERE match_id = ?
                """,
            (rs, rowNum) -> new ServerMatch(
                rs.getObject("match_id", UUID.class),
                rs.getObject("server_id", UUID.class),
                rs.getString("realm_id"),
                rs.getString("status")
            ),
            matchId
        );
    }

    public List<ServerIdentityRecord> findServerIdentities(UUID serverId) {
        return query(
            """
                SELECT
                  si.server_id,
                  si.realm_id,
                  si.server_build_id,
                  si.certificate_fingerprint,
                  ARRAY(
                    SELECT sic.certificate_fingerprint
                    FROM server_identity_certificates sic
                    WHERE sic.server_id = si.server_id
                      AND sic.revoked_at IS NULL
                      AND (
                        (
                          sic.status = 'active'
                          AND sic.valid_from <= NOW()
                          AND sic.expires_at > NOW()
                        )
                        OR (
                          sic.status = 'retiring'
                          AND sic.grace_until IS NOT NULL
                          AND sic.grace_until > NOW()
                        )
                      )
                  ) AS accepted_certificate_fingerprints,
                  si.status,
                  si.allowed_scopes,
                  si.expires_at
                FROM server_identities si
                WHERE si.server_id = ?
                """,
            (rs, rowNum) -> new ServerIdentityRecord(
                rs.getObject("server_id", UUID.class),
                rs.getString("realm_id"),
                rs.getString("server_build_id"),
                rs.getString("certificate_fingerprint"),
                textArray(rs.getArray("accepted_certificate_fingerprints")),
                rs.getString("status"),
                textArray(rs.getArray("allowed_scopes")),
                rs.getObject("expires_at", OffsetDateTime.class)
            ),
            serverId
        );
    }

    public boolean serverIdentityExists(UUID serverId) {
        Boolean exists = queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM server_identities
                  WHERE server_id = ?
                )
                """,
            Boolean.class,
            serverId
        );
        return Boolean.TRUE.equals(exists);
    }

    public void insertServerAuditEvent(
        UUID eventId,
        UUID serverId,
        UUID matchId,
        String action,
        String scope,
        String result,
        String payloadJson,
        OffsetDateTime now
    ) {
        update(
            """
                INSERT INTO server_audit_events(
                  event_id,
                  server_id,
                  match_id,
                  action,
                  scope,
                  result,
                  payload,
                  created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
            eventId,
            serverId,
            matchId,
            action,
            scope,
            result,
            payloadJson,
            now
        );
    }

    private static Set<String> textArray(Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        return Arrays.stream((String[]) array.getArray()).collect(Collectors.toSet());
    }
}
