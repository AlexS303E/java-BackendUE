package com.game.backend.catalog.api;

import com.game.backend.catalog.application.CatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Публичный API для выдачи клиенту активного snapshot каталога.
 */
@RestController
public class CatalogController {
    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * Возвращает актуальный каталог realm вместе с предметами, mount-ами и разрешенными модулями.
     */
    @GetMapping("/catalog/snapshot")
    CatalogSnapshotResponse getSnapshot(@RequestParam(defaultValue = "global") String realmId) {
        return catalogService.getSnapshot(realmId);
    }
}
