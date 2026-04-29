package com.game.backend.presets.api;

import java.util.List;

public record WeaponPresetDto(
    String classTag,
    int presetSlot,
    long catalogVersion,
    long revision,
    boolean sanitized,
    List<WeaponSlotPresetDto> slots
) {
}
