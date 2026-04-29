package com.game.backend.catalog.api;

/**
 * Базовая карточка предмета каталога для клиента.
 */
public record CatalogItemDto(
    String itemId,
    long catalogVersion,
    String itemType,
    String displayName,
    boolean enabled
) {
}
