package com.game.backend.catalog.api;

import com.game.backend.catalog.application.CatalogService;
import org.springframework.http.ResponseEntity;
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
    ResponseEntity<CatalogSnapshotResponse> getSnapshot(
            @RequestParam(value = "realm_id", defaultValue = "global") String realmId
    ) {
        CatalogSnapshotResponse response = catalogService.getSnapshot(realmId);
        return ResponseEntity
                .ok()
                .eTag("catalog-" + response.realmId() + "-" + response.catalogVersion())
                .body(response);
    }
}
