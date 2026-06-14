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
                SELECT server_id, realm_id, server_build_id, certificate_fingerprint, status, allowed_scopes, expires_at
                FROM server_identities
                WHERE server_id = ?
                """,
            (rs, rowNum) -> new ServerIdentityRecord(
                rs.getObject("server_id", UUID.class),
                rs.getString("realm_id"),
                rs.getString("server_build_id"),
                rs.getString("certificate_fingerprint"),
                rs.getString("status"),
                scopes(rs.getArray("allowed_scopes")),
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

    private static Set<String> scopes(Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        return Arrays.stream((String[]) array.getArray()).collect(Collectors.toSet());
    }
}
