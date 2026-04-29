package com.game.backend.serverauth.application;

import com.game.backend.common.api.ApiException;
import com.game.backend.matchprofile.api.BuildMatchProfileRequest;
import com.game.backend.runtimechanges.api.RuntimePresetChangeRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Отвечает за привязку match_id к DS и проверку, что DS работает только со своими матчами.
 */
@Service
public class ServerMatchService {
    private final JdbcTemplate jdbcTemplate;

    public ServerMatchService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Создает assignment для match profile build или проверяет уже существующий assignment.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureAssignedForBuild(ServerIdentity identity, BuildMatchProfileRequest request) {
        ensureRealmMatchesIdentity(identity, request.realmId());
        ensureBuildMatchesIdentity(identity, request.serverBuildId());

        jdbcTemplate.update(
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
                """,
            request.matchId(),
            identity.serverId(),
            request.realmId(),
            OffsetDateTime.now()
        );

        ServerMatch match = loadMatch(request.matchId());
        if (match == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MATCH_ASSIGNMENT_FAILED", "Unable to assign match to server");
        }
        ensureOwnedBy(identity, match);
        ensureRealmMatchesRequest(match, request.realmId());
        ensureMatchIsActive(match);
    }

    /**
     * Проверяет, что runtime operation пришла от DS, которому назначен этот match_id.
     */
    @Transactional(readOnly = true)
    public void ensureAssignedForRuntimeChange(ServerIdentity identity, RuntimePresetChangeRequest request) {
        ensureAssignedForServerOperation(identity, request.matchId(), "Runtime preset changes");
    }

    /**
     * Проверяет, что server operation пришла от DS, которому назначен match_id.
     */
    @Transactional(readOnly = true)
    public void ensureAssignedForServerOperation(ServerIdentity identity, UUID matchId, String operationName) {
        ServerMatch match = loadMatch(matchId);
        if (match == null) {
            throw new ApiException(
                HttpStatus.FORBIDDEN,
                "MATCH_NOT_ASSIGNED",
                operationName + " are allowed only for matches assigned to this server"
            );
        }
        ensureOwnedBy(identity, match);
        ensureRealmMatchesIdentity(identity, match.realmId());
        ensureMatchIsActive(match);
    }

    private ServerMatch loadMatch(UUID matchId) {
        List<ServerMatch> matches = jdbcTemplate.query(
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
        return matches.isEmpty() ? null : matches.getFirst();
    }

    /**
     * Не дает одному DS читать или менять данные матча другого DS.
     */
    private void ensureOwnedBy(ServerIdentity identity, ServerMatch match) {
        if (!match.serverId().equals(identity.serverId())) {
            throw new ApiException(
                HttpStatus.FORBIDDEN,
                "MATCH_ASSIGNED_TO_ANOTHER_SERVER",
                "Match is assigned to another server identity"
            );
        }
    }

    private void ensureRealmMatchesIdentity(ServerIdentity identity, String realmId) {
        if (!identity.realmId().equals(realmId)) {
            throw new ApiException(
                HttpStatus.FORBIDDEN,
                "SERVER_REALM_MISMATCH",
                "Server identity is not allowed to operate in realm: " + realmId
            );
        }
    }

    private void ensureRealmMatchesRequest(ServerMatch match, String realmId) {
        if (!match.realmId().equals(realmId)) {
            throw new ApiException(
                HttpStatus.FORBIDDEN,
                "MATCH_REALM_MISMATCH",
                "Match is assigned to another realm"
            );
        }
    }

    private void ensureBuildMatchesIdentity(ServerIdentity identity, String serverBuildId) {
        if (!identity.serverBuildId().equals(serverBuildId)) {
            throw new ApiException(
                HttpStatus.FORBIDDEN,
                "SERVER_BUILD_MISMATCH",
                "Server build id does not match server identity"
            );
        }
    }

    private void ensureMatchIsActive(ServerMatch match) {
        if (!"creating".equals(match.status()) && !"running".equals(match.status())) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "MATCH_NOT_RUNNING",
                "Match is not accepting server operations"
            );
        }
    }

    private record ServerMatch(
        UUID matchId,
        UUID serverId,
        String realmId,
        String status
    ) {
    }
}
