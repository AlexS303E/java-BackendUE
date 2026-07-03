package com.game.backend.presets.application;

/**
 * Selected outfit item for one clothing slot.
 */
public record OutfitItem(
    String clothingSlotId,
    String itemId
) {
}
