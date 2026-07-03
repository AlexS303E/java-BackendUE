package com.game.backend.catalog.application;

import java.util.List;

/**
 * Published catalog snapshot for one realm and catalog version.
 */
public record CatalogSnapshot(
    String realmId,
    long catalogVersion,
    List<CatalogItem> items,
    List<WeaponMount> weaponMounts,
    List<AllowedModule> allowedModules
) {
}
