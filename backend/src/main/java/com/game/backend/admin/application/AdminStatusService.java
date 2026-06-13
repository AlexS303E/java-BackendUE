package com.game.backend.admin.application;

import com.game.backend.admin.repository.AdminRepository;

import com.game.backend.common.api.ApiException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Собирает read-модель для admin dashboard из operational таблиц backend.
 */
@Service
public class AdminStatusService {
    private final AdminRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final OffsetDateTime startedAt;

    public AdminStatusService(AdminRepository repository, StringRedisTemplate redisTemplate) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.startedAt = OffsetDateTime.now();
    }

    /**
     * Компактный обзор health/counts, который dashboard обновляет периодически.
     */
    public Map<String, Object> overview() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("backend", Map.of(
            "ok", true,
            "uptime", formatDuration(Duration.between(startedAt, OffsetDateTime.now()))
        ));
        response.put("infrastructure", Map.of(
            "databaseOk", databaseOk(),
            "redisOk", redisOk()
        ));
        response.put("catalog", activeCatalog());
        response.put("runtime", Map.of(
            "runningMatches", count("SELECT count(*) FROM server_matches WHERE status = 'running'"),
            "runtimeConflicts", count("SELECT count(*) FROM post_match_pending_changes WHERE status = 'pending'")
        ));
        response.put("outbox", outboxOverview());
        return response;
    }

    public List<Map<String, Object>> servers() {
        return repository.query(
            """
                SELECT server_id, realm_id, server_build_id, status, allowed_scopes, created_at, expires_at, revoked_at
                FROM server_identities
                ORDER BY created_at DESC
                LIMIT 50
                """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("serverId", rs.getObject("server_id", UUID.class));
                row.put("realmId", rs.getString("realm_id"));
                row.put("serverBuildId", rs.getString("server_build_id"));
                row.put("status", rs.getString("status"));
                row.put("allowedScopes", rs.getArray("allowed_scopes") == null ? List.of() : List.of((String[]) rs.getArray("allowed_scopes").getArray()));
                row.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
                row.put("expiresAt", rs.getObject("expires_at", OffsetDateTime.class));
                row.put("revokedAt", rs.getObject("revoked_at", OffsetDateTime.class));
                return row;
            }
        );
    }

    public List<Map<String, Object>> matches() {
        return repository.query(
            """
                SELECT match_id, server_id, realm_id, status, created_at, finished_at
                FROM server_matches
                ORDER BY created_at DESC
                LIMIT 50
                """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("matchId", rs.getObject("match_id", UUID.class));
                row.put("serverId", rs.getObject("server_id", UUID.class));
                row.put("realmId", rs.getString("realm_id"));
                row.put("status", rs.getString("status"));
                row.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
                row.put("finishedAt", rs.getObject("finished_at", OffsetDateTime.class));
                return row;
            }
        );
    }

    public List<Map<String, Object>> recentAudit() {
        return repository.query(
            """
                SELECT event_id, actor_id, action, target_type, target_id, result, created_at
                FROM admin_audit_events
                ORDER BY created_at DESC
                LIMIT 50
                """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("eventId", rs.getObject("event_id", UUID.class));
                row.put("actorId", rs.getString("actor_id"));
                row.put("action", rs.getString("action"));
                row.put("targetType", rs.getString("target_type"));
                row.put("targetId", rs.getString("target_id"));
                row.put("result", rs.getString("result"));
                row.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
                return row;
            }
        );
    }

    public List<Map<String, Object>> searchPlayers(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank()) {
            return List.of();
        }

        UUID playerId = parseUuid(trimmed);
        if (playerId != null) {
            return repository.query(
                """
                    SELECT pa.player_id, pa.login_name, pa.status, ps.access_revision
                    FROM player_accounts pa
                    LEFT JOIN player_access_projection_state ps ON ps.player_id = pa.player_id
                    WHERE pa.player_id = ?
                    """,
                (rs, rowNum) -> playerRow(rs.getObject("player_id", UUID.class), rs.getString("login_name"), rs.getString("status"), rs.getObject("access_revision", Long.class)),
                playerId
            );
        }

        return repository.query(
            """
                SELECT pa.player_id, pa.login_name, pa.status, ps.access_revision
                FROM player_accounts pa
                LEFT JOIN player_access_projection_state ps ON ps.player_id = pa.player_id
                WHERE pa.login_name ILIKE ?
                ORDER BY pa.created_at DESC
                LIMIT 20
                """,
            (rs, rowNum) -> playerRow(rs.getObject("player_id", UUID.class), rs.getString("login_name"), rs.getString("status"), rs.getObject("access_revision", Long.class)),
            "%" + trimmed + "%"
        );
    }

    public Map<String, Object> weaponAccess(UUID playerId, String weaponId, long catalogVersion) {
        List<Map<String, Object>> rows = repository.query(
            """
                SELECT
                  pia.item_id,
                  pia.catalog_version,
                  pia.is_hidden,
                  pia.is_locked_in_shop,
                  pia.is_locked_by_quest,
                  pia.is_disabled,
                  pia.disabled_reason,
                  pia.unlock_hint_code,
                  pia.updated_at,
                  ps.access_revision,
                  ci.is_enabled
                FROM player_item_access pia
                JOIN player_access_projection_state ps ON ps.player_id = pia.player_id
                JOIN catalog_items ci
                  ON ci.item_id = pia.item_id
                 AND ci.catalog_version = pia.catalog_version
                WHERE pia.player_id = ?
                  AND pia.item_id = ?
                  AND pia.catalog_version = ?
                """,
            (rs, rowNum) -> accessRow(
                rs.getString("item_id"),
                rs.getLong("catalog_version"),
                rs.getBoolean("is_hidden"),
                rs.getBoolean("is_locked_in_shop"),
                rs.getBoolean("is_locked_by_quest"),
                rs.getBoolean("is_disabled"),
                rs.getString("disabled_reason"),
                rs.getString("unlock_hint_code"),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getLong("access_revision"),
                rs.getBoolean("is_enabled")
            ),
            playerId,
            weaponId,
            catalogVersion
        );
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCESS_ITEM_NOT_FOUND", "Weapon access state was not found");
        }
        return rows.getFirst();
    }

    public List<Map<String, Object>> weaponAccessAudit(UUID playerId, String weaponId, long catalogVersion) {
        return repository.query(
            """
                SELECT ledger_event_id, event_type, source_type, source_ref, actor_type, actor_id, payload, created_at
                FROM entitlement_ledger
                WHERE player_id = ?
                  AND item_id = ?
                  AND catalog_version = ?
                ORDER BY created_at DESC
                LIMIT 50
                """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ledgerEventId", rs.getObject("ledger_event_id", UUID.class));
                row.put("eventType", rs.getString("event_type"));
                row.put("action", rs.getString("event_type"));
                row.put("sourceType", rs.getString("source_type"));
                row.put("sourceRef", rs.getString("source_ref"));
                row.put("actorType", rs.getString("actor_type"));
                row.put("actorId", rs.getString("actor_id"));
                row.put("result", "success");
                row.put("payload", rs.getString("payload"));
                row.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
                return row;
            },
            playerId,
            weaponId,
            catalogVersion
        );
    }

    private boolean databaseOk() {
        try {
            Integer result = repository.queryForObject("SELECT 1", Integer.class);
            return result != null && result == 1;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean redisOk() {
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping());
        } catch (RedisConnectionFailureException exception) {
            return false;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Map<String, Object> activeCatalog() {
        List<Map<String, Object>> rows = repository.query(
            """
                SELECT catalog_version, deployment_state, allow_new_matches, allow_existing_matches, activated_at
                FROM catalog_deployments
                WHERE realm_id = 'global'
                  AND deployment_state = 'active'
                ORDER BY activated_at DESC NULLS LAST, catalog_version DESC
                LIMIT 1
                """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("activeVersion", rs.getLong("catalog_version"));
                row.put("deploymentState", rs.getString("deployment_state"));
                row.put("allowNewMatches", rs.getBoolean("allow_new_matches"));
                row.put("allowExistingMatches", rs.getBoolean("allow_existing_matches"));
                row.put("activatedAt", rs.getObject("activated_at", OffsetDateTime.class));
                return row;
            }
        );
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private Map<String, Object> outboxOverview() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("pending", 0L);
        counts.put("failed", 0L);
        counts.put("processed", 0L);
        for (Map<String, Object> row : repository.queryForList("SELECT status, count(*) AS count FROM outbox_events GROUP BY status")) {
            counts.put((String) row.get("status"), ((Number) row.get("count")).longValue());
        }

        OffsetDateTime oldest = repository.queryForObject(
            "SELECT min(created_at) FROM outbox_events WHERE status = 'pending'",
            OffsetDateTime.class
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("pending", counts.get("pending"));
        response.put("failed", counts.get("failed"));
        response.put("processed", counts.get("processed"));
        response.put("oldestPendingAge", oldest == null ? "0s" : formatDuration(Duration.between(oldest, OffsetDateTime.now())));
        return response;
    }

    private long count(String sql) {
        Long count = repository.queryForObject(sql, Long.class);
        return count == null ? 0 : count;
    }

    private Map<String, Object> playerRow(UUID playerId, String loginName, String status, Long accessRevision) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("playerId", playerId);
        row.put("loginName", loginName);
        row.put("status", status);
        row.put("accessRevision", accessRevision);
        return row;
    }

    private Map<String, Object> accessRow(
        String itemId,
        long catalogVersion,
        boolean hidden,
        boolean lockedInShop,
        boolean lockedByQuest,
        boolean disabled,
        String disabledReason,
        String unlockHintCode,
        OffsetDateTime updatedAt,
        long accessRevision,
        boolean catalogEnabled
    ) {
        boolean playerCanUse = !hidden && !lockedInShop && !lockedByQuest && !disabled;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("itemId", itemId);
        row.put("catalogVersion", catalogVersion);
        row.put("isHidden", hidden);
        row.put("isLockedInShop", lockedInShop);
        row.put("isLockedByQuest", lockedByQuest);
        row.put("isDisabled", disabled);
        row.put("disabledReason", disabledReason);
        row.put("unlockHintCode", unlockHintCode);
        row.put("updatedAt", updatedAt);
        row.put("accessRevision", accessRevision);
        row.put("catalogEnabled", catalogEnabled);
        row.put("playerCanUse", playerCanUse);
        row.put("effectiveCanUse", catalogEnabled && playerCanUse);
        return row;
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + remainingSeconds + "s";
        }
        return remainingSeconds + "s";
    }
}
