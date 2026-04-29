package com.game.backend.presets.api;

import java.util.List;

public record WeaponSlotPresetDto(
    String weaponSlotId,
    String selectedWeaponId,
    List<ModuleSelectionDto> modules
) {
}
