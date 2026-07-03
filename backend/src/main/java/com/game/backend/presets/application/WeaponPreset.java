package com.game.backend.presets.application;

import java.util.List;

/**
 * Player weapon preset application snapshot.
 */
public record WeaponPreset(
    String classTag,
    int presetSlot,
    long catalogVersion,
    long revision,
    boolean sanitized,
    List<WeaponSlotPreset> slots
) {
}
