package com.game.backend.admin.api;

import com.game.backend.admin.application.AdminControlService;
import com.game.backend.admin.application.CurrentAdmin;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    @PostMapping("/admin/control/players/{playerId}/invalidate-cache")
    Map<String, Object> invalidatePlayerCache(Authentication authentication, @PathVariable UUID playerId) {
        return adminControlService.invalidatePlayerCache(CurrentAdmin.require(authentication), playerId);
    }

    /**
     * Отзывает server identity.
     */
    @PostMapping("/admin/control/server-identities/{serverId}/revoke")
    Map<String, Object> revokeServerIdentity(Authentication authentication, @PathVariable UUID serverId) {
        return adminControlService.revokeServerIdentity(CurrentAdmin.require(authentication), serverId);
    }

    /**
     * Возвращает failed outbox события в pending для повторной обработки worker-ом.
     */
    @PostMapping("/admin/control/outbox/retry-failed")
    Map<String, Object> retryFailedOutbox(Authentication authentication) {
        return adminControlService.retryFailedOutbox(CurrentAdmin.require(authentication));
    }

    /**
     * Adapter для dashboard action -> полный admin access update над оружием.
     */
    @PostMapping("/admin/control/players/{playerId}/weapon-access")
    AdminItemAccessUpdateResponse changeWeaponAccess(
        Authentication authentication,
        @PathVariable UUID playerId,
        @Valid @RequestBody AdminWeaponAccessControlRequest request
    ) {
        return adminControlService.changeWeaponAccess(CurrentAdmin.require(authentication), playerId, request);
    }
}
