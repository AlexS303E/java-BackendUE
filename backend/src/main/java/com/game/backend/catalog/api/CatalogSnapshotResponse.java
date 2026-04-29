package com.game.backend.catalog.api;

import java.util.List;

public record CatalogSnapshotResponse(
    String realmId,
    long catalogVersion,
    List<CatalogItemDto> items,
    List<WeaponMountDto> weaponMounts,
    List<AllowedModuleDto> allowedModules
) {
}
