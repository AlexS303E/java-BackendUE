package com.game.backend.admin.application;

import com.game.backend.admin.repository.AdminRepository;

import com.game.backend.common.api.ApiException;
import com.game.backend.matchprofile.application.MatchProfileInvalidationService;
import org.springframework.http.HttpStatus;
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
    private static final String CONTROL_CACHE_SCOPE = "admin.control.player_cache.invalidate";
    private static final String CONTROL_CACHE_ROUTE = "/admin/control/players/{player_id}/invalidate-cache";
    private static final String CONTROL_REVOKE_SCOPE = "admin.control.server_identity.revoke";
    private static final String CONTROL_REVOKE_ROUTE = "/admin/control/server-identities/{server_id}/revoke";
    private static final String CONTROL_OUTBOX_SCOPE = "admin.control.outbox.retry_failed";
    private static final String CONTROL_OUTBOX_ROUTE = "/admin/control/outbox/retry-failed";

    private final AdminRepository repository;
    private final AdminPlayerAccessService adminPlayerAccessService;
    private final AdminMutationIdempotencyService idempotencyService;
    private final AdminStatusService adminStatusService;
    private final AdminAuditService adminAuditService;
    private final MatchProfileInvalidationService matchProfileInvalidationService;

    public AdminControlService(
        AdminRepository repository,
        AdminPlayerAccessService adminPlayerAccessService,
        AdminMutationIdempotencyService idempotencyService,
        AdminStatusService adminStatusService,
        AdminAuditService adminAuditService,
        MatchProfileInvalidationService matchProfileInvalidationService
    ) {
        this.repository = repository;
        this.adminPlayerAccessService = adminPlayerAccessService;
        this.idempotencyService = idempotencyService;
        this.adminStatusService = adminStatusService;
        this.adminAuditService = adminAuditService;
        this.matchProfileInvalidationService = matchProfileInvalidationService;
    }

    /**
     * Ручная инвалидация profile snapshots игрока из dashboard.
     */
    public AdminControlPlayerCacheInvalidationResult invalidatePlayerCache(
        AdminIdentity admin,
        String idempotencyKey,
        UUID playerId,
        AdminControlReasonCommand request
    ) {
        Map<String, Object> idempotencyPayload = controlPayload("player_id", playerId, request);
        return idempotencyService.execute(
            admin,
            CONTROL_CACHE_SCOPE,
            CONTROL_CACHE_ROUTE,
            idempotencyKey,
            idempotencyPayload,
            AdminControlPlayerCacheInvalidationResult.class,
            () -> invalidatePlayerCacheOnce(admin, playerId, request, requestHash(CONTROL_CACHE_SCOPE, CONTROL_CACHE_ROUTE, idempotencyPayload))
        );
    }

    @Transactional
    protected AdminControlPlayerCacheInvalidationResult invalidatePlayerCacheOnce(
        AdminIdentity admin,
        UUID playerId,
        AdminControlReasonCommand request,
        String requestHash
    ) {
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
            requestHash,
            "success",
            auditPayload(request, Map.of("stale_match_profiles", staleProfiles))
        );
        return new AdminControlPlayerCacheInvalidationResult(playerId, staleProfiles);
    }

    /**
     * Отзывает active/expired server identity и фиксирует admin audit.
     */
    public AdminControlServerIdentityRevokeResult revokeServerIdentity(
        AdminIdentity admin,
        String idempotencyKey,
        UUID serverId,
        AdminControlReasonCommand request
    ) {
        Map<String, Object> idempotencyPayload = controlPayload("server_id", serverId, request);
        return idempotencyService.execute(
            admin,
            CONTROL_REVOKE_SCOPE,
            CONTROL_REVOKE_ROUTE,
            idempotencyKey,
            idempotencyPayload,
            AdminControlServerIdentityRevokeResult.class,
            () -> revokeServerIdentityOnce(admin, serverId, request, requestHash(CONTROL_REVOKE_SCOPE, CONTROL_REVOKE_ROUTE, idempotencyPayload))
        );
    }

    @Transactional
    protected AdminControlServerIdentityRevokeResult revokeServerIdentityOnce(
        AdminIdentity admin,
        UUID serverId,
        AdminControlReasonCommand request,
        String requestHash
    ) {
        int updated = repository.revokeServerIdentity(serverId, OffsetDateTime.now());
        adminAuditService.record(
            admin,
            "server_identity.revoke",
            "server_identity",
            serverId.toString(),
            requestHash,
            updated > 0 ? "success" : "failed",
            auditPayload(request, Map.of("updated", updated))
        );
        if (updated == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SERVER_IDENTITY_NOT_FOUND_OR_ALREADY_REVOKED", "Server identity was not found or already revoked");
        }
        return new AdminControlServerIdentityRevokeResult(serverId, "revoked");
    }

    /**
     * Возвращает failed outbox events в pending для повторной обработки worker-ом.
     */
    public AdminControlOutboxRetryResult retryFailedOutbox(
        AdminIdentity admin,
        String idempotencyKey,
        AdminControlReasonCommand request
    ) {
        Map<String, Object> idempotencyPayload = controlPayload("action", "retry_failed", request);
        return idempotencyService.execute(
            admin,
            CONTROL_OUTBOX_SCOPE,
            CONTROL_OUTBOX_ROUTE,
            idempotencyKey,
            idempotencyPayload,
            AdminControlOutboxRetryResult.class,
            () -> retryFailedOutboxOnce(admin, request, requestHash(CONTROL_OUTBOX_SCOPE, CONTROL_OUTBOX_ROUTE, idempotencyPayload))
        );
    }

    @Transactional
    protected AdminControlOutboxRetryResult retryFailedOutboxOnce(
        AdminIdentity admin,
        AdminControlReasonCommand request,
        String requestHash
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        int retried = repository.retryFailedOutboxEvents(now);
        adminAuditService.record(
            admin,
            "outbox.retry_failed",
            "outbox",
            "failed",
            requestHash,
            "success",
            auditPayload(request, Map.of("retried", retried))
        );
        return new AdminControlOutboxRetryResult(retried);
    }

    /**
     * Переводит dashboard action в полный access update, чтобы сохранить один источник истины для ledger/audit/outbox.
     */
    public AdminItemAccessUpdateResult changeWeaponAccess(
        AdminIdentity admin,
        String idempotencyKey,
        UUID playerId,
        AdminWeaponAccessControlCommand request
    ) {
        AdminStatusService.AdminWeaponAccessStatus current = adminStatusService.weaponAccess(playerId, request.weaponId(), request.catalogVersion());
        AccessFlags flags = AccessFlags.from(current);
        AccessFlags updated = flags.apply(request.action(), request);

        AdminItemAccessUpdateCommand updateRequest = new AdminItemAccessUpdateCommand(
            request.catalogVersion(),
            updated.hidden(),
            updated.lockedInShop(),
            updated.lockedByQuest(),
            updated.disabled(),
            updated.disabledReason(),
            updated.unlockHintCode(),
            null,
            request.reason(),
            request.action()
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
        static AccessFlags from(AdminStatusService.AdminWeaponAccessStatus status) {
            return new AccessFlags(
                status.hidden(),
                status.lockedInShop(),
                status.lockedByQuest(),
                status.disabled(),
                status.disabledReason(),
                status.unlockHintCode()
            );
        }

        AccessFlags apply(String action, AdminWeaponAccessControlCommand request) {
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

        private static String reasonOrComment(AdminWeaponAccessControlCommand request) {
            if (request.comment() != null && !request.comment().isBlank()) {
                return request.comment().trim();
            }
            return request.reason();
        }
    }

    private String requestHash(String operationScope, String routeFingerprint, Map<String, Object> payload) {
        return idempotencyService.requestHash(operationScope, routeFingerprint, payload);
    }

    private static Map<String, Object> controlPayload(String targetKey, Object targetValue, AdminControlReasonCommand request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(targetKey, targetValue);
        payload.put("reason", request.reason());
        if (request.comment() != null && !request.comment().isBlank()) {
            payload.put("comment", request.comment().trim());
        }
        return payload;
    }

    private static Map<String, Object> auditPayload(AdminControlReasonCommand request, Map<String, Object> values) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", request.reason());
        if (request.comment() != null && !request.comment().isBlank()) {
            payload.put("comment", request.comment().trim());
        }
        payload.putAll(values);
        return payload;
    }
}
