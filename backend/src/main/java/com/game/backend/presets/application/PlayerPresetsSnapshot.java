package com.game.backend.presets.application;

import java.util.List;
import java.util.UUID;

/**
 * Full player presets snapshot for the loadout UI.
 */
public record PlayerPresetsSnapshot(
    UUID playerId,
    List<WeaponPreset> weaponPresets,
    List<OutfitPreset> outfitPresets
) {
}
