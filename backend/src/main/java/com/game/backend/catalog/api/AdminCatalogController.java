package com.game.backend.catalog.api;

import com.game.backend.admin.application.CurrentAdmin;
import com.game.backend.catalog.application.CatalogLifecycleService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
        @Valid @RequestBody CatalogPublishRequest request
    ) {
        return catalogLifecycleService.publish(CurrentAdmin.require(authentication), request);
    }

    /**
     * Переключает realm обратно на previous или явно указанную catalog_version.
     */
    @PostMapping("/admin/catalog/rollback")
    CatalogLifecycleResponse rollback(
        Authentication authentication,
        @Valid @RequestBody CatalogRollbackRequest request
    ) {
        return catalogLifecycleService.rollback(CurrentAdmin.require(authentication), request);
    }
}
