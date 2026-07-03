package com.game.backend.presets.application;

import java.util.List;

/**
 * Player outfit preset application snapshot.
 */
public record OutfitPreset(
    String teamTag,
    String classTag,
    int outfitPresetSlot,
    long catalogVersion,
    long revision,
    boolean sanitized,
    List<OutfitItem> items
) {
}
