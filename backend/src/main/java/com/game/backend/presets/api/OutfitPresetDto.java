package com.game.backend.presets.api;

import java.util.List;

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
