package com.game.backend.matchprofile.api;

/**
 * Outfit item, который DS должен выдать игроку в матче.
 */
public record MatchOutfitItemDto(
    String clothingSlotId,
    String itemId
) {
}
