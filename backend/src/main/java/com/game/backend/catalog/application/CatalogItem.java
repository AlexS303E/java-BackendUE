package com.game.backend.catalog.application;

/**
 * Catalog item state for a published catalog version.
 */
public record CatalogItem(
    String itemId,
    long catalogVersion,
    String itemType,
    String displayName,
    boolean enabled
) {
}
