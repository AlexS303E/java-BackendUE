package com.game.backend.matchprofile.application;

/**
 * Outfit item included in a match profile snapshot.
 */
public record MatchProfileOutfitItem(
    String clothingSlotId,
    String itemId
) {
}
