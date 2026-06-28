package com.game.backend.admin.api;

import com.game.backend.admin.application.AdminControlService;
import com.game.backend.admin.application.CurrentAdmin;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Admin control endpoints, которые вызываются dashboard-ом для ручных операторских действий.
 */
@RestController
public class AdminControlController {
    private final AdminControlService adminControlService;

    public AdminControlController(AdminControlService adminControlService) {
        this.adminControlService = adminControlService;
    }

    /**
     * Помечает match profile snapshots игрока устаревшими вручную.
     */
    @PostMapping("/admin/control/players/{player_id}/invalidate-cache")
    Map<String, Object> invalidatePlayerCache(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @PathVariable("player_id") UUID playerId,
        @Valid @RequestBody AdminControlReasonRequest request
    ) {
        return adminControlService.invalidatePlayerCache(CurrentAdmin.require(authentication), idempotencyKey, playerId, request);
    }

    /**
     * Отзывает server identity.
     */
    @PostMapping("/admin/control/server-identities/{server_id}/revoke")
    Map<String, Object> revokeServerIdentity(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @PathVariable("server_id") UUID serverId,
        @Valid @RequestBody AdminControlReasonRequest request
    ) {
        return adminControlService.revokeServerIdentity(CurrentAdmin.require(authentication), idempotencyKey, serverId, request);
    }

    /**
     * Возвращает failed outbox события в pending для повторной обработки worker-ом.
     */
    @PostMapping("/admin/control/outbox/retry-failed")
    Map<String, Object> retryFailedOutbox(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AdminControlReasonRequest request
    ) {
        return adminControlService.retryFailedOutbox(CurrentAdmin.require(authentication), idempotencyKey, request);
    }

    /**
     * Adapter для dashboard action -> полный admin access update над оружием.
     */
    @PostMapping("/admin/control/players/{player_id}/weapon-access")
    AdminItemAccessUpdateResponse changeWeaponAccess(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable("player_id") UUID playerId,
            @Valid @RequestBody AdminWeaponAccessControlRequest request
    ) {
        return adminControlService.changeWeaponAccess(CurrentAdmin.require(authentication), idempotencyKey, playerId, request);
    }
}
