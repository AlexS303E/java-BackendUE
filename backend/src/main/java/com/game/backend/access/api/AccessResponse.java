package com.game.backend.access.api;

import java.util.List;
import java.util.UUID;

/**
 * Снимок access projection игрока для выбранной версии каталога.
 */
public record AccessResponse(
    UUID playerId,
    long catalogVersion,
    long accessRevision,
    List<AccessItemDto> items
) {
}
