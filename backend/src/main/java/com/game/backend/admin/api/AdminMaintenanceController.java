package com.game.backend.admin.api;

import com.game.backend.admin.application.AdminAccessMaintenanceService;
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
        return adminAccessMaintenanceService.rebuildProjection(CurrentAdmin.require(authentication), idempotencyKey, request);
    }

    @PostMapping("/admin/cache/invalidate-player")
    AdminCacheInvalidateResponse invalidatePlayerCache(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AdminCacheInvalidateRequest request
    ) {
        return adminAccessMaintenanceService.invalidatePlayerCache(CurrentAdmin.require(authentication), idempotencyKey, request);
    }

    @PostMapping("/admin/server-identities/revoke")
    AdminServerIdentityRevokeResponse revokeServerIdentity(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AdminServerIdentityRevokeRequest request
    ) {
        return adminAccessMaintenanceService.revokeServerIdentity(CurrentAdmin.require(authentication), idempotencyKey, request);
    }
}
