package com.game.backend.catalog.api;

import com.game.backend.catalog.application.AllowedModule;
import com.game.backend.catalog.application.CatalogItem;
import com.game.backend.catalog.application.CatalogService;
import com.game.backend.catalog.application.CatalogSnapshot;
import com.game.backend.catalog.application.WeaponMount;
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
        CatalogSnapshot snapshot = catalogService.getSnapshot(realmId);
        CatalogSnapshotResponse response = toResponse(snapshot);
        return ResponseEntity
                .ok()
                .eTag("catalog-" + response.realmId() + "-" + response.catalogVersion())
                .body(response);
    }

    private CatalogSnapshotResponse toResponse(CatalogSnapshot snapshot) {
        return new CatalogSnapshotResponse(
            snapshot.realmId(),
            snapshot.catalogVersion(),
            snapshot.items().stream().map(this::toDto).toList(),
            snapshot.weaponMounts().stream().map(this::toDto).toList(),
            snapshot.allowedModules().stream().map(this::toDto).toList()
        );
    }

    private CatalogItemDto toDto(CatalogItem item) {
        return new CatalogItemDto(
            item.itemId(),
            item.catalogVersion(),
            item.itemType(),
            item.displayName(),
            item.enabled()
        );
    }

    private WeaponMountDto toDto(WeaponMount mount) {
        return new WeaponMountDto(
            mount.mountId(),
            mount.catalogVersion(),
            mount.weaponId(),
            mount.mountType(),
            mount.mountIndex(),
            mount.required(),
            mount.displayOrder()
        );
    }

    private AllowedModuleDto toDto(AllowedModule module) {
        return new AllowedModuleDto(
            module.mountId(),
            module.moduleId(),
            module.catalogVersion()
        );
    }
}
