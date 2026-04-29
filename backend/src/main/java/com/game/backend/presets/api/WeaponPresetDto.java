package com.game.backend.presets.api;

import java.util.List;

/**
 * Weapon preset игрока с ревизией и выбранными weapon slots.
 */
public record WeaponPresetDto(
    String classTag,
    int presetSlot,
    long catalogVersion,
    long revision,
    boolean sanitized,
    List<WeaponSlotPresetDto> slots
) {
}
