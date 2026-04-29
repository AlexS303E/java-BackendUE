package com.game.backend.catalog.api;

import java.util.List;

/**
 * Полный read-only snapshot каталога, который нужен клиенту и DS для валидации loadout.
 */
public record CatalogSnapshotResponse(
    String realmId,
    long catalogVersion,
    List<CatalogItemDto> items,
    List<WeaponMountDto> weaponMounts,
    List<AllowedModuleDto> allowedModules
) {
}
