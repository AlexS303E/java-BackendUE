package com.game.backend.catalog.api;

import com.game.backend.admin.application.CurrentAdmin;
import com.game.backend.catalog.application.CatalogLifecycleResult;
import com.game.backend.catalog.application.CatalogLifecycleService;
import com.game.backend.catalog.application.CatalogPublishCommand;
import com.game.backend.catalog.application.CatalogRollbackCommand;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints для минимального lifecycle каталога: publish и rollback.
 */
@RestController
public class AdminCatalogController {
    private final CatalogLifecycleService catalogLifecycleService;

    public AdminCatalogController(CatalogLifecycleService catalogLifecycleService) {
        this.catalogLifecycleService = catalogLifecycleService;
    }

    /**
     * Делает validated/canary catalog_version активной для новых матчей realm.
     */
    @PostMapping("/admin/catalog/publish")
    CatalogLifecycleResponse publish(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody CatalogPublishRequest request
    ) {
        CatalogPublishCommand command = new CatalogPublishCommand(
            request.realmId(),
            request.catalogVersion(),
            request.rolloutPercent(),
            request.allowExistingMatches(),
            request.reason()
        );
        return toResponse(catalogLifecycleService.publish(CurrentAdmin.require(authentication), idempotencyKey, command));
    }

    /**
     * Переключает realm обратно на previous или явно указанную catalog_version.
     */
    @PostMapping("/admin/catalog/rollback")
    CatalogLifecycleResponse rollback(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody CatalogRollbackRequest request
    ) {
        CatalogRollbackCommand command = new CatalogRollbackCommand(
            request.realmId(),
            request.targetCatalogVersion(),
            request.reason()
        );
        return toResponse(catalogLifecycleService.rollback(CurrentAdmin.require(authentication), idempotencyKey, command));
    }

    private CatalogLifecycleResponse toResponse(CatalogLifecycleResult result) {
        return new CatalogLifecycleResponse(
            result.operationId(),
            result.realmId(),
            result.previousCatalogVersion(),
            result.activeCatalogVersion(),
            result.action(),
            result.migratedWeaponPresets(),
            result.migratedOutfitPresets(),
            result.migratedAccessPlayers(),
            result.staleMatchProfiles()
        );
    }
}
