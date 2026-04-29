package com.game.backend.presets.api;

import java.util.List;

/**
 * Текущий выбор оружия и модулей в одном weapon slot.
 */
public record WeaponSlotPresetDto(
    String weaponSlotId,
    String selectedWeaponId,
    List<ModuleSelectionDto> modules
) {
}
