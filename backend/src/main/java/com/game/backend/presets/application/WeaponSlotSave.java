package com.game.backend.presets.application;

import java.util.List;

/**
 * Weapon slot save command with selected modules.
 */
public record WeaponSlotSave(
    String weaponSlotId,
    String weaponId,
    List<ModuleSave> modules
) {
}
