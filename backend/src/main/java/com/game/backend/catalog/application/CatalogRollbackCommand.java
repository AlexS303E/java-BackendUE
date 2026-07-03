package com.game.backend.catalog.application;

/**
 * Application command for rolling a realm back to a previous catalog version.
 */
public record CatalogRollbackCommand(
    String realmId,
    Long targetCatalogVersion,
    String reason
) {
}
