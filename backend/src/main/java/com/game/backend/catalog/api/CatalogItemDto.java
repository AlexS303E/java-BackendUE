package com.game.backend.catalog.api;

public record CatalogItemDto(
    String itemId,
    long catalogVersion,
    String itemType,
    String displayName,
    boolean enabled
) {
}
