package com.game.backend.catalog.application;

/**
 * Application command for publishing a catalog version.
 */
public record CatalogPublishCommand(
    String realmId,
    Long catalogVersion,
    Integer rolloutPercent,
    Boolean allowExistingMatches,
    String reason
) {
}
