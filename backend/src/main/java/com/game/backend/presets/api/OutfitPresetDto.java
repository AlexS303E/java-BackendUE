package com.game.backend.presets.api;

import java.util.List;

/**
 * Outfit preset игрока для команды, класса и версии каталога.
 */
public record OutfitPresetDto(
    String teamTag,
    String classTag,
    int outfitPresetSlot,
    long catalogVersion,
    long revision,
    boolean sanitized,
    List<OutfitItemDto> items
) {
}
