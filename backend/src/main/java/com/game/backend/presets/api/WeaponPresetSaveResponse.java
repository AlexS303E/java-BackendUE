package com.game.backend.presets.api;

import java.util.List;
import java.util.UUID;

public record WeaponPresetSaveResponse(
    UUID playerId,
    String classTag,
    int presetSlot,
    long catalogVersion,
    long revision,
    List<WeaponSlotPresetDto> slots
) {
}
