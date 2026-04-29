package com.game.backend.presets.api;

import java.util.List;
import java.util.UUID;

public record PlayerPresetsResponse(
    UUID playerId,
    List<WeaponPresetDto> weaponPresets,
    List<OutfitPresetDto> outfitPresets
) {
}
