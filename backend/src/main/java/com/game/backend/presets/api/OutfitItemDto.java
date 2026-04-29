package com.game.backend.presets.api;

/**
 * Предмет одежды, выбранный в outfit slot.
 */
public record OutfitItemDto(
    String clothingSlotId,
    String itemId
) {
}
