package com.game.backend.admin.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class AdminRepository extends JdbcRepository {
    public record ItemAccessFlags(
        boolean hidden,
        boolean lockedInShop,
        boolean lockedByQuest,
        boolean disabled,
        String disabledReason,
        String unlockHintCode,
        String unlockHintPayloadJson
    ) {
    }

    public record ExistingAdminIdempotencyRecord(String requestHash, String responseBody) {
    }

    public AdminRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public boolean databasePingOk() {
        Integer result = queryForObject("SELECT 1", Integer.class);
        return result != null && result == 1;
    }

    public long countRunningMatches() {
        return count("SELECT count(*) FROM server_matches WHERE status = 'running'");
    }

    public long countPendingRuntimeConflicts() {
        return count("SELECT count(*) FROM post_match_pending_changes WHERE status = 'pending'");
    }

    public List<Map<String, Object>> listServers() {
        return query(
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

    public List<Map<String, Object>> listMatches() {
        return query(
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

    public List<Map<String, Object>> listRecentAuditEvents() {
        return query(
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

    public void recordAdminAuditEvent(
        UUID eventId,
        String actorId,
        String action,
        String targetType,
        String targetId,
        String requestHash,
        String payloadJson,
        String result,
        OffsetDateTime createdAt
    ) {
        update(
            """
                INSERT INTO admin_audit_events(
                  event_id,
                  actor_id,
                  action,
                  target_type,
                  target_id,
                  request_hash,
                  payload,
                  result,
                  created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
            eventId,
            actorId,
            action,
            targetType,
            targetId,
            requestHash,
            payloadJson,
            result,
            createdAt
        );
    }

    public int revokeServerIdentity(UUID serverId, OffsetDateTime revokedAt) {
        return update(
            """
                UPDATE server_identities
                SET status = 'revoked',
                    revoked_at = ?
                WHERE server_id = ?
                  AND status <> 'revoked'
                """,
            revokedAt,
            serverId
        );
    }

    public int retryFailedOutboxEvents(OffsetDateTime nextAttemptAt) {
        return update(
            """
                UPDATE outbox_events
                SET status = 'pending',
                    next_attempt_at = ?,
                    last_error = null
                WHERE status = 'failed'
                """,
            nextAttemptAt
        );
    }

    public void deleteExpiredAdminIdempotencyRecord(
        String operationScope,
        String actorId,
        String idempotencyKey,
        OffsetDateTime now
    ) {
        update(
            """
                DELETE FROM api_idempotency_records
                WHERE operation_scope = ?
                  AND actor_id = ?
                  AND idempotency_key = ?
                  AND expires_at <= ?
                """,
            operationScope,
            actorId,
            idempotencyKey,
            now
        );
    }

    public List<ExistingAdminIdempotencyRecord> findAdminIdempotencyRecords(
        String operationScope,
        String actorId,
        String idempotencyKey
    ) {
        return query(
            """
                SELECT request_hash, response_body::text AS response_body
                FROM api_idempotency_records
                WHERE operation_scope = ?
                  AND actor_id = ?
                  AND idempotency_key = ?
                """,
            (rs, rowNum) -> new ExistingAdminIdempotencyRecord(
                rs.getString("request_hash"),
                rs.getString("response_body")
            ),
            operationScope,
            actorId,
            idempotencyKey
        );
    }

    public void insertAdminIdempotencyRecord(
        String operationScope,
        String actorId,
        String routeFingerprint,
        String idempotencyKey,
        String requestHash,
        int statusCode,
        String responseBodyJson,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt
    ) {
        update(
            """
                INSERT INTO api_idempotency_records(
                  operation_scope,
                  actor_id,
                  route_fingerprint,
                  idempotency_key,
                  request_hash,
                  status_code,
                  response_body,
                  created_at,
                  expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
            operationScope,
            actorId,
            routeFingerprint,
            idempotencyKey,
            requestHash,
            statusCode,
            responseBodyJson,
            createdAt,
            expiresAt
        );
    }

    public List<Map<String, Object>> findPlayer(UUID playerId) {
        return query(
            """
                SELECT pa.player_id, pa.login_name, pa.status, ps.access_revision
                FROM player_accounts pa
                LEFT JOIN player_access_projection_state ps ON ps.player_id = pa.player_id
                WHERE pa.player_id = ?
                """,
            (rs, rowNum) -> playerRow(
                rs.getObject("player_id", UUID.class),
                rs.getString("login_name"),
                rs.getString("status"),
                rs.getObject("access_revision", Long.class)
            ),
            playerId
        );
    }

    public List<Map<String, Object>> searchPlayersByLogin(String loginFragment) {
        return query(
            """
                SELECT pa.player_id, pa.login_name, pa.status, ps.access_revision
                FROM player_accounts pa
                LEFT JOIN player_access_projection_state ps ON ps.player_id = pa.player_id
                WHERE pa.login_name ILIKE ?
                ORDER BY pa.created_at DESC
                LIMIT 20
                """,
            (rs, rowNum) -> playerRow(
                rs.getObject("player_id", UUID.class),
                rs.getString("login_name"),
                rs.getString("status"),
                rs.getObject("access_revision", Long.class)
            ),
            "%" + loginFragment + "%"
        );
    }

    public List<Map<String, Object>> findWeaponAccess(UUID playerId, String weaponId, long catalogVersion) {
        return query(
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
    }

    public List<ItemAccessFlags> findItemAccessFlags(UUID playerId, String itemId, long catalogVersion) {
        return query(
            """
                SELECT
                  is_hidden,
                  is_locked_in_shop,
                  is_locked_by_quest,
                  is_disabled,
                  disabled_reason,
                  unlock_hint_code,
                  unlock_hint_payload::text AS unlock_hint_payload
                FROM player_item_access
                WHERE player_id = ?
                  AND item_id = ?
                  AND catalog_version = ?
                """,
            (rs, rowNum) -> new ItemAccessFlags(
                rs.getBoolean("is_hidden"),
                rs.getBoolean("is_locked_in_shop"),
                rs.getBoolean("is_locked_by_quest"),
                rs.getBoolean("is_disabled"),
                rs.getString("disabled_reason"),
                rs.getString("unlock_hint_code"),
                rs.getString("unlock_hint_payload")
            ),
            playerId,
            itemId,
            catalogVersion
        );
    }

    public List<Map<String, Object>> listWeaponAccessAudit(UUID playerId, String weaponId, long catalogVersion) {
        return query(
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

    public Map<String, Object> activeCatalog() {
        List<Map<String, Object>> rows = query(
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

    public Map<String, Long> outboxStatusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("pending", 0L);
        counts.put("failed", 0L);
        counts.put("processed", 0L);
        for (Map<String, Object> row : queryForList("SELECT status, count(*) AS count FROM outbox_events GROUP BY status")) {
            counts.put((String) row.get("status"), ((Number) row.get("count")).longValue());
        }
        return counts;
    }

    public OffsetDateTime oldestPendingOutboxCreatedAt() {
        return queryForObject(
            "SELECT min(created_at) FROM outbox_events WHERE status = 'pending'",
            OffsetDateTime.class
        );
    }

    private long count(String sql) {
        Long count = queryForObject(sql, Long.class);
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
}
