package com.game.backend.presets.application;

import java.util.List;
import java.util.UUID;

/**
 * Result of a successful weapon preset save.
 */
public record WeaponPresetSaveResult(
    UUID playerId,
    String classTag,
    int presetSlot,
    long catalogVersion,
    long revision,
    List<WeaponSlotPreset> slots
) {
}
