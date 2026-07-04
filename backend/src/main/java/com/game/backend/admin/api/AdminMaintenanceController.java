package com.game.backend.admin.api;

import com.game.backend.admin.application.AdminCacheInvalidateCommand;
import com.game.backend.admin.application.AdminCacheInvalidateResult;
import com.game.backend.admin.application.AdminAccessMaintenanceService;
import com.game.backend.admin.application.AdminProjectionRebuildCommand;
import com.game.backend.admin.application.AdminProjectionRebuildResult;
import com.game.backend.admin.application.AdminServerIdentityRevokeCommand;
import com.game.backend.admin.application.AdminServerIdentityRevokeResult;
import com.game.backend.admin.application.CurrentAdmin;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * ТЗ-совместимые admin maintenance endpoints.
 */
@RestController
public class AdminMaintenanceController {
    private final AdminAccessMaintenanceService adminAccessMaintenanceService;

    public AdminMaintenanceController(AdminAccessMaintenanceService adminAccessMaintenanceService) {
        this.adminAccessMaintenanceService = adminAccessMaintenanceService;
    }

    @PostMapping("/admin/access/rebuild-projection")
    AdminProjectionRebuildResponse rebuildProjection(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AdminProjectionRebuildRequest request
    ) {
        return toResponse(adminAccessMaintenanceService.rebuildProjection(
            CurrentAdmin.require(authentication),
            idempotencyKey,
            new AdminProjectionRebuildCommand(request.playerId(), request.reason())
        ));
    }

    @PostMapping("/admin/cache/invalidate-player")
    AdminCacheInvalidateResponse invalidatePlayerCache(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AdminCacheInvalidateRequest request
    ) {
        return toResponse(adminAccessMaintenanceService.invalidatePlayerCache(
            CurrentAdmin.require(authentication),
            idempotencyKey,
            new AdminCacheInvalidateCommand(request.playerId(), request.reason())
        ));
    }

    @PostMapping("/admin/server-identities/revoke")
    AdminServerIdentityRevokeResponse revokeServerIdentity(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AdminServerIdentityRevokeRequest request
    ) {
        return toResponse(adminAccessMaintenanceService.revokeServerIdentity(
            CurrentAdmin.require(authentication),
            idempotencyKey,
            new AdminServerIdentityRevokeCommand(request.serverId(), request.reason())
        ));
    }

    private AdminProjectionRebuildResponse toResponse(AdminProjectionRebuildResult result) {
        return new AdminProjectionRebuildResponse(
            result.playerId(),
            result.accessRevision(),
            result.itemsRebuilt(),
            result.ledgerEventsApplied(),
            result.staleMatchProfiles(),
            result.lastLedgerEventId()
        );
    }

    private AdminCacheInvalidateResponse toResponse(AdminCacheInvalidateResult result) {
        return new AdminCacheInvalidateResponse(result.playerId(), result.staleMatchProfiles());
    }

    private AdminServerIdentityRevokeResponse toResponse(AdminServerIdentityRevokeResult result) {
        return new AdminServerIdentityRevokeResponse(result.serverId(), result.status(), result.updated());
    }
}
