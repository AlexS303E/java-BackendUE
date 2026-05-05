package com.game.backend.catalog.api;

import java.util.UUID;

/**
 * Итог publish/rollback операции catalog lifecycle.
 */
public record CatalogLifecycleResponse(
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
