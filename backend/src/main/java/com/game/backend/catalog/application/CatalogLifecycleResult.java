package com.game.backend.catalog.application;

import java.util.UUID;

/**
 * Result of a catalog publish or rollback operation.
 */
public record CatalogLifecycleResult(
    UUID operationId,
    String realmId,
    long previousCatalogVersion,
    long activeCatalogVersion,
    String action,
    int migratedWeaponPresets,
    int migratedOutfitPresets,
    int migratedAccessPlayers,
    int staleMatchProfiles
) {
}
