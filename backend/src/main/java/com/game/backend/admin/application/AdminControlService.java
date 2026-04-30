package com.game.backend.admin.application;

import com.game.backend.admin.api.AdminItemAccessUpdateRequest;
import com.game.backend.admin.api.AdminItemAccessUpdateResponse;
import com.game.backend.admin.api.AdminWeaponAccessControlRequest;
import com.game.backend.common.api.ApiException;
import com.game.backend.matchprofile.application.MatchProfileInvalidationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Выполняет операторские команды admin dashboard поверх уже существующих доменных сервисов.
 */
@Service
public class AdminControlService {
    private final JdbcTemplate jdbcTemplate;
    private final AdminPlayerAccessService adminPlayerAccessService;
    private final AdminStatusService adminStatusService;
    private final AdminAuditService adminAuditService;
    private final MatchProfileInvalidationService matchProfileInvalidationService;

    public AdminControlService(
        JdbcTemplate jdbcTemplate,
        AdminPlayerAccessService adminPlayerAccessService,
        AdminStatusService adminStatusService,
        AdminAuditService adminAuditService,
        MatchProfileInvalidationService matchProfileInvalidationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.adminPlayerAccessService = adminPlayerAccessService;
        this.adminStatusService = adminStatusService;
        this.adminAuditService = adminAuditService;
        this.matchProfileInvalidationService = matchProfileInvalidationService;
    }

    /**
     * Ручная инвалидация profile snapshots игрока из dashboard.
     */
    @Transactional
    public Map<String, Object> invalidatePlayerCache(AdminIdentity admin, UUID playerId) {
        OffsetDateTime now = OffsetDateTime.now();
        UUID eventId = UUID.randomUUID();
        int staleProfiles = matchProfileInvalidationService.invalidateForPlayer(
            playerId,
            "admin_cache_invalidation",
            eventId,
            now
        );
        adminAuditService.record(
            admin,
            "player_cache.invalidate",
            "player",
            playerId.toString(),
            null,
            "success",
            Map.of("stale_match_profiles", staleProfiles)
        );
        return Map.of(
            "player_id", playerId,
            "stale_match_profiles", staleProfiles
        );
    }

    /**
     * Отзывает active/expired server identity и фиксирует admin audit.
     */
    @Transactional
    public Map<String, Object> revokeServerIdentity(AdminIdentity admin, UUID serverId) {
        int updated = jdbcTemplate.update(
            """
                UPDATE server_identities
                SET status = 'revoked',
                    revoked_at = ?
                WHERE server_id = ?
                  AND status <> 'revoked'
                """,
            OffsetDateTime.now(),
            serverId
        );
        adminAuditService.record(
            admin,
            "server_identity.revoke",
            "server_identity",
            serverId.toString(),
            null,
            updated > 0 ? "success" : "failed",
            Map.of("updated", updated)
        );
        if (updated == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SERVER_IDENTITY_NOT_FOUND_OR_ALREADY_REVOKED", "Server identity was not found or already revoked");
        }
        return Map.of(
            "server_id", serverId,
            "status", "revoked"
        );
    }

    /**
     * Возвращает failed outbox events в pending для повторной обработки worker-ом.
     */
    @Transactional
    public Map<String, Object> retryFailedOutbox(AdminIdentity admin) {
        OffsetDateTime now = OffsetDateTime.now();
        int retried = jdbcTemplate.update(
            """
                UPDATE outbox_events
                SET status = 'pending',
                    next_attempt_at = ?,
                    last_error = null
                WHERE status = 'failed'
                """,
            now
        );
        adminAuditService.record(
            admin,
            "outbox.retry_failed",
            "outbox",
            "failed",
            null,
            "success",
            Map.of("retried", retried)
        );
        return Map.of("retried", retried);
    }

    /**
     * Переводит dashboard action в полный access update, чтобы сохранить один источник истины для ledger/audit/outbox.
     */
    public AdminItemAccessUpdateResponse changeWeaponAccess(
        AdminIdentity admin,
        UUID playerId,
        AdminWeaponAccessControlRequest request
    ) {
        Map<String, Object> current = adminStatusService.weaponAccess(playerId, request.weaponId(), request.catalogVersion());
        AccessFlags flags = AccessFlags.from(current);
        AccessFlags updated = flags.apply(request.action(), request);
        String idempotencyKey = "dashboard:" + UUID.randomUUID();

        AdminItemAccessUpdateRequest updateRequest = new AdminItemAccessUpdateRequest(
            request.catalogVersion(),
            updated.hidden(),
            updated.lockedInShop(),
            updated.lockedByQuest(),
            updated.disabled(),
            updated.disabledReason(),
            updated.unlockHintCode(),
            null,
            request.reason()
        );
        return adminPlayerAccessService.updateItemAccess(
            admin,
            idempotencyKey,
            playerId,
            request.weaponId(),
            updateRequest
        );
    }

    private record AccessFlags(
        boolean hidden,
        boolean lockedInShop,
        boolean lockedByQuest,
        boolean disabled,
        String disabledReason,
        String unlockHintCode
    ) {
        static AccessFlags from(Map<String, Object> row) {
            return new AccessFlags(
                bool(value(row, "isHidden", "is_hidden")),
                bool(value(row, "isLockedInShop", "is_locked_in_shop")),
                bool(value(row, "isLockedByQuest", "is_locked_by_quest")),
                bool(value(row, "isDisabled", "is_disabled")),
                (String) value(row, "disabledReason", "disabled_reason"),
                (String) value(row, "unlockHintCode", "unlock_hint_code")
            );
        }

        AccessFlags apply(String action, AdminWeaponAccessControlRequest request) {
            String normalizedAction = action == null ? "" : action.trim();
            return switch (normalizedAction) {
                case "shop_lock" -> new AccessFlags(hidden, true, lockedByQuest, disabled, disabledReason, "shop_locked");
                case "shop_unlock" -> new AccessFlags(hidden, false, lockedByQuest, disabled, disabledReason, unlockHintCode);
                case "quest_lock" -> new AccessFlags(hidden, lockedInShop, true, disabled, disabledReason, "quest_locked");
                case "quest_unlock" -> new AccessFlags(hidden, lockedInShop, false, disabled, disabledReason, unlockHintCode);
                case "item_disable" -> new AccessFlags(hidden, lockedInShop, lockedByQuest, true, reasonOrComment(request), "admin_disabled");
                case "item_enable" -> new AccessFlags(hidden, lockedInShop, lockedByQuest, false, null, unlockHintCode);
                case "hide_item" -> new AccessFlags(true, lockedInShop, lockedByQuest, disabled, disabledReason, "hidden_by_admin");
                case "reveal_item" -> new AccessFlags(false, lockedInShop, lockedByQuest, disabled, disabledReason, unlockHintCode);
                default -> throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unsupported weapon access action: " + action);
            };
        }

        private static boolean bool(Object value) {
            return value instanceof Boolean booleanValue && booleanValue;
        }

        private static Object value(Map<String, Object> row, String primaryKey, String fallbackKey) {
            return row.containsKey(primaryKey) ? row.get(primaryKey) : row.get(fallbackKey);
        }

        private static String reasonOrComment(AdminWeaponAccessControlRequest request) {
            if (request.comment() != null && !request.comment().isBlank()) {
                return request.comment().trim();
            }
            return request.reason();
        }
    }
}
