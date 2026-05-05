package com.game.backend.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.admin.api.AdminCacheInvalidateRequest;
import com.game.backend.admin.api.AdminCacheInvalidateResponse;
import com.game.backend.admin.api.AdminProjectionRebuildRequest;
import com.game.backend.admin.api.AdminProjectionRebuildResponse;
import com.game.backend.admin.api.AdminServerIdentityRevokeRequest;
import com.game.backend.admin.api.AdminServerIdentityRevokeResponse;
import com.game.backend.cache.RedisCacheService;
import com.game.backend.common.api.ApiException;
import com.game.backend.matchprofile.application.MatchProfileInvalidationService;
import com.game.backend.outbox.application.OutboxService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ТЗ-совместимые admin maintenance команды: projection rebuild, cache invalidation, server identity revoke.
 */
@Service
public class AdminAccessMaintenanceService {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AdminMutationIdempotencyService idempotencyService;
    private final AdminAuditService adminAuditService;
    private final OutboxService outboxService;
    private final MatchProfileInvalidationService matchProfileInvalidationService;
    private final RedisCacheService cacheService;

    public AdminAccessMaintenanceService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        AdminMutationIdempotencyService idempotencyService,
        AdminAuditService adminAuditService,
        OutboxService outboxService,
        MatchProfileInvalidationService matchProfileInvalidationService,
        RedisCacheService cacheService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.idempotencyService = idempotencyService;
        this.adminAuditService = adminAuditService;
        this.outboxService = outboxService;
        this.matchProfileInvalidationService = matchProfileInvalidationService;
        this.cacheService = cacheService;
    }

    /**
     * Пересобирает player_item_access из immutable entitlement_ledger.
     */
    public AdminProjectionRebuildResponse rebuildProjection(
        AdminIdentity admin,
        String idempotencyKey,
        AdminProjectionRebuildRequest request
    ) {
        return idempotencyService.execute(
            admin,
            "admin.access.rebuild_projection",
            "/admin/access/rebuild-projection",
            idempotencyKey,
            request,
            AdminProjectionRebuildResponse.class,
            () -> rebuildProjectionOnce(admin, request)
        );
    }

    /**
     * Помечает profile snapshots игрока устаревшими.
     */
    public AdminCacheInvalidateResponse invalidatePlayerCache(
        AdminIdentity admin,
        String idempotencyKey,
        AdminCacheInvalidateRequest request
    ) {
        return idempotencyService.execute(
            admin,
            "admin.cache.invalidate_player",
            "/admin/cache/invalidate-player",
            idempotencyKey,
            request,
            AdminCacheInvalidateResponse.class,
            () -> invalidatePlayerCacheOnce(admin, request)
        );
    }

    /**
     * Отзывает server identity через ТЗ-совместимый endpoint.
     */
    public AdminServerIdentityRevokeResponse revokeServerIdentity(
        AdminIdentity admin,
        String idempotencyKey,
        AdminServerIdentityRevokeRequest request
    ) {
        return idempotencyService.execute(
            admin,
            "admin.server_identity.revoke",
            "/admin/server-identities/revoke",
            idempotencyKey,
            request,
            AdminServerIdentityRevokeResponse.class,
            () -> revokeServerIdentityOnce(admin, request)
        );
    }

    @Transactional
    protected AdminProjectionRebuildResponse rebuildProjectionOnce(AdminIdentity admin, AdminProjectionRebuildRequest request) {
        ensurePlayerExists(request.playerId());
        OffsetDateTime now = OffsetDateTime.now();
        long currentRevision = lockOrCreateProjectionState(request.playerId(), now);
        List<LedgerRow> ledgerRows = ledgerRows(request.playerId());
        Map<ProjectionKey, ProjectionFlags> projection = reduce(ledgerRows);
        UUID lastLedgerEventId = ledgerRows.isEmpty() ? null : ledgerRows.getLast().ledgerEventId();
        long nextRevision = currentRevision + 1;

        jdbcTemplate.update("DELETE FROM player_item_access WHERE player_id = ?", request.playerId());
        for (Map.Entry<ProjectionKey, ProjectionFlags> entry : projection.entrySet()) {
            insertProjectionRow(request.playerId(), entry.getKey(), entry.getValue(), now);
        }
        jdbcTemplate.update(
            """
                UPDATE player_access_projection_state
                SET access_revision = ?,
                    projection_rebuilt_at = ?,
                    last_ledger_event_id = ?
                WHERE player_id = ?
                """,
            nextRevision,
            now,
            lastLedgerEventId,
            request.playerId()
        );
        cacheService.evictPlayerAccess(request.playerId());
        UUID eventId = UUID.randomUUID();
        int staleProfiles = matchProfileInvalidationService.invalidateForPlayer(
            request.playerId(),
            "admin_access_rebuild_projection",
            eventId,
            now
        );
        outboxService.record(
            "player_access.projection_rebuilt",
            "player_access",
            request.playerId().toString(),
            1,
            Map.of(
                "player_id", request.playerId(),
                "access_revision", nextRevision,
                "items_rebuilt", projection.size(),
                "ledger_events_applied", ledgerRows.size(),
                "stale_match_profiles", staleProfiles,
                "actor_id", admin.actorId(),
                "reason", request.reason()
            ),
            now
        );
        adminAuditService.record(
            admin,
            "access.rebuild_projection",
            "player",
            request.playerId().toString(),
            null,
            "success",
            Map.of(
                "reason", request.reason(),
                "access_revision", nextRevision,
                "items_rebuilt", projection.size(),
                "ledger_events_applied", ledgerRows.size(),
                "stale_match_profiles", staleProfiles
            )
        );
        return new AdminProjectionRebuildResponse(
            request.playerId(),
            nextRevision,
            projection.size(),
            ledgerRows.size(),
            staleProfiles,
            lastLedgerEventId
        );
    }

    @Transactional
    protected AdminCacheInvalidateResponse invalidatePlayerCacheOnce(AdminIdentity admin, AdminCacheInvalidateRequest request) {
        ensurePlayerExists(request.playerId());
        OffsetDateTime now = OffsetDateTime.now();
        UUID eventId = UUID.randomUUID();
        int staleProfiles = matchProfileInvalidationService.invalidateForPlayer(
            request.playerId(),
            "admin_cache_invalidate_player",
            eventId,
            now
        );
        cacheService.evictPlayerAccess(request.playerId());
        outboxService.record(
            "player_cache.invalidated",
            "player",
            request.playerId().toString(),
            1,
            Map.of(
                "player_id", request.playerId(),
                "stale_match_profiles", staleProfiles,
                "actor_id", admin.actorId(),
                "reason", request.reason()
            ),
            now
        );
        adminAuditService.record(
            admin,
            "cache.invalidate_player",
            "player",
            request.playerId().toString(),
            null,
            "success",
            Map.of("reason", request.reason(), "stale_match_profiles", staleProfiles)
        );
        return new AdminCacheInvalidateResponse(request.playerId(), staleProfiles);
    }

    @Transactional
    protected AdminServerIdentityRevokeResponse revokeServerIdentityOnce(AdminIdentity admin, AdminServerIdentityRevokeRequest request) {
        List<String> statuses = jdbcTemplate.queryForList(
            "SELECT status FROM server_identities WHERE server_id = ? FOR UPDATE",
            String.class,
            request.serverId()
        );
        if (statuses.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SERVER_IDENTITY_NOT_FOUND", "Server identity was not found");
        }
        boolean updated = jdbcTemplate.update(
            """
                UPDATE server_identities
                SET status = 'revoked',
                    revoked_at = ?
                WHERE server_id = ?
                  AND status <> 'revoked'
                """,
            OffsetDateTime.now(),
            request.serverId()
        ) > 0;
        OffsetDateTime now = OffsetDateTime.now();
        outboxService.record(
            "server_identity.revoked",
            "server_identity",
            request.serverId().toString(),
            1,
            Map.of(
                "server_id", request.serverId(),
                "updated", updated,
                "actor_id", admin.actorId(),
                "reason", request.reason()
            ),
            now
        );
        adminAuditService.record(
            admin,
            "server_identity.revoke",
            "server_identity",
            request.serverId().toString(),
            null,
            "success",
            Map.of("reason", request.reason(), "updated", updated)
        );
        return new AdminServerIdentityRevokeResponse(request.serverId(), "revoked", updated);
    }

    private void ensurePlayerExists(UUID playerId) {
        Boolean exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM player_accounts WHERE player_id = ?)",
            Boolean.class,
            playerId
        );
        if (!Boolean.TRUE.equals(exists)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND", "Player was not found");
        }
    }

    private long lockOrCreateProjectionState(UUID playerId, OffsetDateTime now) {
        List<Long> revisions = jdbcTemplate.queryForList(
            "SELECT access_revision FROM player_access_projection_state WHERE player_id = ? FOR UPDATE",
            Long.class,
            playerId
        );
        if (!revisions.isEmpty()) {
            return revisions.getFirst();
        }
        jdbcTemplate.update(
            """
                INSERT INTO player_access_projection_state(
                  player_id,
                  access_revision,
                  projection_rebuilt_at
                )
                VALUES (?, 0, ?)
                """,
            playerId,
            now
        );
        return 0L;
    }

    private List<LedgerRow> ledgerRows(UUID playerId) {
        return jdbcTemplate.query(
            """
                SELECT
                  ledger_event_id,
                  item_id,
                  catalog_version,
                  event_type,
                  payload::text AS payload
                FROM entitlement_ledger
                WHERE player_id = ?
                ORDER BY created_at ASC, ledger_event_id ASC
                """,
            (rs, rowNum) -> new LedgerRow(
                rs.getObject("ledger_event_id", UUID.class),
                rs.getString("item_id"),
                rs.getLong("catalog_version"),
                rs.getString("event_type"),
                parsePayload(rs.getString("payload"))
            ),
            playerId
        );
    }

    private Map<ProjectionKey, ProjectionFlags> reduce(List<LedgerRow> rows) {
        Map<ProjectionKey, ProjectionFlags> projection = new LinkedHashMap<>();
        for (LedgerRow row : rows) {
            ProjectionKey key = new ProjectionKey(row.itemId(), row.catalogVersion());
            ProjectionFlags flags = projection.getOrDefault(key, ProjectionFlags.defaultAllow());
            projection.put(key, applyLedger(row, flags));
        }
        return projection;
    }

    private ProjectionFlags applyLedger(LedgerRow row, ProjectionFlags flags) {
        return switch (row.eventType()) {
            case "hide_item" -> flags.withHidden(true).withHint("hidden", null);
            case "reveal_item" -> flags.withHidden(false).normalized();
            case "shop_lock" -> flags.withLockedInShop(true).withHint("buy_in_shop", null);
            case "shop_unlock" -> flags.withLockedInShop(false).normalized();
            case "quest_lock" -> flags.withLockedByQuest(true).withHint("complete_quest", null);
            case "quest_unlock" -> flags.withLockedByQuest(false).normalized();
            case "item_disable" -> flags.withDisabled(true, "ledger_disable").withHint("admin_disabled", null);
            case "item_enable" -> flags.withDisabled(false, null).normalized();
            case "compensation_unlock" -> flags.withLockedInShop(false).withLockedByQuest(false).normalized();
            case "admin_override" -> applyAdminOverride(row.payload(), flags);
            default -> flags;
        };
    }

    @SuppressWarnings("unchecked")
    private ProjectionFlags applyAdminOverride(Map<String, Object> payload, ProjectionFlags fallback) {
        if (payload == null || !(payload.get("flags") instanceof Map<?, ?> rawFlags)) {
            return fallback;
        }
        Map<String, Object> flags = (Map<String, Object>) rawFlags;
        return new ProjectionFlags(
            bool(flags.get("hidden"), fallback.hidden()),
            bool(flags.get("locked_in_shop"), fallback.lockedInShop()),
            bool(flags.get("locked_by_quest"), fallback.lockedByQuest()),
            bool(flags.get("disabled"), fallback.disabled()),
            string(flags.get("disabled_reason")),
            string(flags.get("unlock_hint_code")),
            mapOrNull(flags.get("unlock_hint_payload"))
        ).normalized();
    }

    private void insertProjectionRow(UUID playerId, ProjectionKey key, ProjectionFlags flags, OffsetDateTime now) {
        jdbcTemplate.update(
            """
                INSERT INTO player_item_access(
                  player_id,
                  item_id,
                  catalog_version,
                  is_hidden,
                  is_locked_in_shop,
                  is_locked_by_quest,
                  is_disabled,
                  disabled_reason,
                  unlock_hint_code,
                  unlock_hint_payload,
                  updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
            playerId,
            key.itemId(),
            key.catalogVersion(),
            flags.hidden(),
            flags.lockedInShop(),
            flags.lockedByQuest(),
            flags.disabled(),
            flags.disabledReason(),
            flags.unlockHintCode(),
            toJsonOrNull(flags.unlockHintPayload()),
            now
        );
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JSON_MAP);
        } catch (JsonProcessingException exception) {
            return Map.of("parse_error", true);
        }
    }

    private String toJsonOrNull(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize JSON payload", exception);
        }
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean booleanValue ? booleanValue : fallback;
    }

    private static String string(Object value) {
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrNull(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            return (Map<String, Object>) mapValue;
        }
        return null;
    }

    private record LedgerRow(
        UUID ledgerEventId,
        String itemId,
        long catalogVersion,
        String eventType,
        Map<String, Object> payload
    ) {
    }

    private record ProjectionKey(String itemId, long catalogVersion) {
    }

    private record ProjectionFlags(
        boolean hidden,
        boolean lockedInShop,
        boolean lockedByQuest,
        boolean disabled,
        String disabledReason,
        String unlockHintCode,
        Map<String, Object> unlockHintPayload
    ) {
        static ProjectionFlags defaultAllow() {
            return new ProjectionFlags(false, false, false, false, null, null, null);
        }

        ProjectionFlags withHidden(boolean value) {
            return new ProjectionFlags(value, lockedInShop, lockedByQuest, disabled, disabledReason, unlockHintCode, unlockHintPayload);
        }

        ProjectionFlags withLockedInShop(boolean value) {
            return new ProjectionFlags(hidden, value, lockedByQuest, disabled, disabledReason, unlockHintCode, unlockHintPayload);
        }

        ProjectionFlags withLockedByQuest(boolean value) {
            return new ProjectionFlags(hidden, lockedInShop, value, disabled, disabledReason, unlockHintCode, unlockHintPayload);
        }

        ProjectionFlags withDisabled(boolean value, String reason) {
            return new ProjectionFlags(hidden, lockedInShop, lockedByQuest, value, reason, unlockHintCode, unlockHintPayload);
        }

        ProjectionFlags withHint(String code, Map<String, Object> payload) {
            return new ProjectionFlags(hidden, lockedInShop, lockedByQuest, disabled, disabledReason, code, payload);
        }

        ProjectionFlags normalized() {
            if (hidden) {
                return new ProjectionFlags(true, lockedInShop, lockedByQuest, disabled, disabledReason, "hidden", unlockHintPayload);
            }
            if (disabled) {
                return new ProjectionFlags(false, lockedInShop, lockedByQuest, true, disabledReason, "admin_disabled", unlockHintPayload);
            }
            if (lockedInShop) {
                return new ProjectionFlags(false, true, lockedByQuest, false, null, nonBlank(unlockHintCode, "buy_in_shop"), unlockHintPayload);
            }
            if (lockedByQuest) {
                return new ProjectionFlags(false, false, true, false, null, nonBlank(unlockHintCode, "complete_quest"), unlockHintPayload);
            }
            return defaultAllow();
        }

        private static String nonBlank(String value, String fallback) {
            if (value != null && !value.isBlank()) {
                return value;
            }
            return fallback;
        }
    }
}
