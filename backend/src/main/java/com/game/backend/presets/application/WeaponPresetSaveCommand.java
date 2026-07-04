package com.game.backend.presets.application;

import java.util.List;

/**
 * Full weapon preset save command.
 */
public record WeaponPresetSaveCommand(
    long catalogVersion,
    List<WeaponSlotSave> slots
) {
}
