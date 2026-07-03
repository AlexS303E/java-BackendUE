package com.game.backend.presets.application;

import java.util.List;

/**
 * Selected weapon and modules for one weapon slot.
 */
public record WeaponSlotPreset(
    String weaponSlotId,
    String selectedWeaponId,
    List<ModuleSelection> modules
) {
}
